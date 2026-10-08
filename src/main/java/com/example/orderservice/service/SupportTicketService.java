package com.example.orderservice.service;

import com.example.orderservice.dto.NewSupportTicket;
import com.example.orderservice.dto.SupportTicketView;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.SupportMessage;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.SupportMessageRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Problem with this order" support requests. A customer opens one against their own order (optionally naming the
 * product and linking a photo), staff reply from cart.html and the customer is emailed each reply, and staff resolve
 * it when done - usually after acting through the existing cancel / return / per-item refund flows. A customer reply
 * re-opens a resolved request for REOPEN_DAYS; after that they open a new one.
 *
 * Limits: one unresolved request per order (add to it instead), MAX_OPEN_PER_CUSTOMER unresolved in total, and
 * message length caps - the same "stop one number flooding the queue" reasoning as product Q&A.
 */
@Service
public class SupportTicketService {
    static final int MIN_MESSAGE_LENGTH = 5;
    static final int MAX_MESSAGE_LENGTH = 1000;
    static final int MAX_OPEN_PER_CUSTOMER = 5;
    static final int REOPEN_DAYS = 30;
    private static final Set<SupportTicket.Status> UNRESOLVED = EnumSet.of(SupportTicket.Status.OPEN, SupportTicket.Status.ANSWERED);

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final CartRepository orders;
    private final CustomerAccountRepository accounts;
    private final MailService mailService;
    private final Clock clock;
    private final String staffEmail;

    public SupportTicketService(SupportTicketRepository tickets, SupportMessageRepository messages, CartRepository orders,
                                CustomerAccountRepository accounts, MailService mailService, Clock clock,
                                @Value("${digest.to:}") String staffEmail) {
        this.tickets = tickets;
        this.messages = messages;
        this.orders = orders;
        this.accounts = accounts;
        this.mailService = mailService;
        this.clock = clock;
        this.staffEmail = staffEmail == null ? "" : staffEmail.trim();
    }

    // ---------- customer ----------

    @Transactional
    public SupportTicketView open(NewSupportTicket request) {
        SupportTicket.Category category = parseCategory(request.category());
        String body = messageText(request.message());
        String photoUrl = photoUrl(request.photoUrl());
        Cart order = orders.findById(request.orderId())
                .filter(o -> o.getCustomerPhno() == request.customerPhno())
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            throw new ProductException("This order's payment isn't confirmed yet - please wait for it before reporting a problem");
        }
        if (request.productId() != null && order.getOrderItems().stream()
                .map(OrderItem::getProductId).noneMatch(id -> id == request.productId().intValue())) {
            throw new ProductException("That product is not in this order");
        }
        List<SupportTicket> openForOrder = tickets.findByOrderIdAndStatusIn(order.getOrderId(), UNRESOLVED);
        if (!openForOrder.isEmpty()) {
            throw new ProductException("You already have an open request for this order (#" + openForOrder.get(0).getId()
                    + ") - add a message to it instead");
        }
        if (tickets.countByCustomerPhnoAndStatusIn(request.customerPhno(), UNRESOLVED) >= MAX_OPEN_PER_CUSTOMER) {
            throw new ProductException("You already have " + MAX_OPEN_PER_CUSTOMER
                    + " open requests - please wait for those to be answered");
        }
        Instant now = clock.instant();
        SupportTicket ticket = new SupportTicket();
        ticket.setCustomerPhno(request.customerPhno());
        ticket.setOrderId(order.getOrderId());
        ticket.setProductId(request.productId());
        ticket.setCategory(category);
        ticket.setStatus(SupportTicket.Status.OPEN);
        ticket.setPhotoUrl(photoUrl);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticket = tickets.save(ticket);
        addMessage(ticket.getId(), SupportMessage.Author.CUSTOMER, body, now);
        notifyStaff(ticket, body, true);
        return view(ticket);
    }

    public List<SupportTicketView> mine(long phno) {
        return tickets.findByCustomerPhnoOrderByIdDesc(phno).stream().map(this::view).toList();
    }

    @Transactional
    public SupportTicketView customerReply(long ticketId, long phno, String text) {
        SupportTicket ticket = tickets.findById(ticketId)
                .filter(t -> t.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Support request not found"));
        String body = messageText(text);
        Instant now = clock.instant();
        if (ticket.getStatus() == SupportTicket.Status.RESOLVED) {
            if (ticket.getResolvedAt() != null && ticket.getResolvedAt().isBefore(now.minus(Duration.ofDays(REOPEN_DAYS)))) {
                throw new ProductException("This request was closed more than " + REOPEN_DAYS
                        + " days ago - please open a new one");
            }
            ticket.setResolvedAt(null);
            ticket.setResolutionNote(null);
        }
        ticket.setStatus(SupportTicket.Status.OPEN);
        ticket.setUpdatedAt(now);
        tickets.save(ticket);
        addMessage(ticketId, SupportMessage.Author.CUSTOMER, body, now);
        notifyStaff(ticket, body, false);
        return view(ticket);
    }

    // ---------- staff ----------

    // status null/blank/UNRESOLVED = OPEN and ANSWERED; ALL = everything; otherwise that one status. OPEN requests
    // come first, then the rest, each oldest-touched first, so the queue reads as "who has waited longest".
    public List<SupportTicket> queue(String status) {
        String wanted = status == null || status.isBlank() ? "UNRESOLVED" : status.trim().toUpperCase(Locale.ROOT);
        Set<SupportTicket.Status> statuses;
        switch (wanted) {
            case "UNRESOLVED" -> statuses = UNRESOLVED;
            case "ALL" -> statuses = EnumSet.allOf(SupportTicket.Status.class);
            default -> {
                try {
                    statuses = EnumSet.of(SupportTicket.Status.valueOf(wanted));
                } catch (IllegalArgumentException e) {
                    throw new ProductException("status must be UNRESOLVED, ALL, OPEN, ANSWERED or RESOLVED");
                }
            }
        }
        return tickets.findByStatusInOrderByUpdatedAtAsc(statuses).stream()
                .sorted(Comparator.comparing((SupportTicket t) -> t.getStatus() != SupportTicket.Status.OPEN))
                .toList();
    }

    public SupportTicketView get(long ticketId) {
        return view(load(ticketId));
    }

    @Transactional
    public SupportTicketView staffReply(long ticketId, String text) {
        SupportTicket ticket = load(ticketId);
        String body = messageText(text);
        Instant now = clock.instant();
        ticket.setStatus(SupportTicket.Status.ANSWERED);
        ticket.setResolvedAt(null);
        ticket.setUpdatedAt(now);
        tickets.save(ticket);
        addMessage(ticketId, SupportMessage.Author.STAFF, body, now);
        emailCustomer(ticket, "Reply to your support request #" + ticket.getId(),
                "We've replied to your request about order #" + ticket.getOrderId() + ":\n\n" + body
                        + "\n\nYou can answer from \"My account\" in the shop.\n");
        return view(ticket);
    }

    @Transactional
    public SupportTicketView resolve(long ticketId, String note) {
        SupportTicket ticket = load(ticketId);
        if (ticket.getStatus() == SupportTicket.Status.RESOLVED) {
            throw new ProductException("This request is already resolved");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > 500) {
            throw new ProductException("Resolution note must be at most 500 characters");
        }
        Instant now = clock.instant();
        ticket.setStatus(SupportTicket.Status.RESOLVED);
        ticket.setResolutionNote(trimmed);
        ticket.setResolvedAt(now);
        ticket.setUpdatedAt(now);
        tickets.save(ticket);
        emailCustomer(ticket, "Your support request #" + ticket.getId() + " is resolved",
                "We've marked your request about order #" + ticket.getOrderId() + " as resolved."
                        + (trimmed == null ? "" : "\n\n" + trimmed)
                        + "\n\nIf something still isn't right, just reply from \"My account\" in the shop within "
                        + REOPEN_DAYS + " days and we'll pick it up again.\n");
        return view(ticket);
    }

    // ---------- helpers ----------

    private SupportTicket load(long ticketId) {
        return tickets.findById(ticketId).orElseThrow(() -> new OrderNotFoundException("Support request not found"));
    }

    private SupportTicketView view(SupportTicket ticket) {
        SupportTicketView.OrderInfo info = orders.findById(ticket.getOrderId())
                .map(o -> new SupportTicketView.OrderInfo(o.getOrderId(), String.valueOf(o.getStatus()),
                        String.valueOf(o.getPaymentMethod()), o.isPaid(), o.getTotalPrice(), o.getRefundedAmount()))
                .orElse(null);
        return new SupportTicketView(ticket, messages.findByTicketIdOrderByIdAsc(ticket.getId()), info);
    }

    private void addMessage(long ticketId, SupportMessage.Author author, String body, Instant at) {
        SupportMessage m = new SupportMessage();
        m.setTicketId(ticketId);
        m.setAuthor(author);
        m.setBody(body);
        m.setCreatedAt(at);
        messages.save(m);
    }

    // Order-related, not promotional, so the marketing opt-out doesn't apply. A failed send is logged by MailService
    // and never blocks the reply itself - the customer still sees it in "My account".
    private void emailCustomer(SupportTicket ticket, String subject, String body) {
        String email = accounts.findById(ticket.getCustomerPhno()).map(CustomerAccount::getEmail).orElse(null);
        if (email != null && !email.isBlank()) {
            mailService.send(email, subject, "Hi,\n\n" + body);
        }
    }

    // Optional heads-up to the store (ADMIN_EMAIL, the same address as the daily digest).
    private void notifyStaff(SupportTicket ticket, String body, boolean isNew) {
        if (staffEmail.isEmpty()) {
            return;
        }
        mailService.send(staffEmail,
                (isNew ? "New support request #" : "Customer replied to support request #") + ticket.getId()
                        + " (" + ticket.getCategory() + ", order #" + ticket.getOrderId() + ")",
                body + "\n\nOpen the Support Requests card in the admin dashboard to reply.\n");
    }

    private static SupportTicket.Category parseCategory(String value) {
        try {
            return SupportTicket.Category.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ProductException("category must be one of DAMAGED, MISSING_ITEM, WRONG_ITEM, NOT_DELIVERED, PAYMENT, OTHER");
        }
    }

    private static String messageText(String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() < MIN_MESSAGE_LENGTH || body.length() > MAX_MESSAGE_LENGTH) {
            throw new ProductException("Message must be " + MIN_MESSAGE_LENGTH + " to " + MAX_MESSAGE_LENGTH + " characters");
        }
        return body;
    }

    private static String photoUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.trim();
        if (url.length() > 500 || url.chars().anyMatch(Character::isWhitespace)
                || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new ProductException("Photo link must be an http(s) URL of at most 500 characters");
        }
        return url;
    }
}
