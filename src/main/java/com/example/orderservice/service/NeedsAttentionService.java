package com.example.orderservice.service;

import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.NeedsAttention;
import com.example.orderservice.dto.NeedsAttention.Item;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.RefundFailureRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that is waiting on the shop owner, in one list: support requests the store has not answered, product
 * questions with no answer, reviews customers reported, products at or below their low-stock threshold, UPI orders
 * stuck past their payment deadline, and notifications that never reached the customer. Rows that depend on
 * ProductService (reports, low stock) say "unavailable" rather than failing the whole list when it is down.
 */
@Service
public class NeedsAttentionService {
    private static final Logger log = LoggerFactory.getLogger(NeedsAttentionService.class);
    static final int FLAGGED_LIMIT = 200;
    static final int MAX_ORDER_IDS = 10;
    static final Duration NOTIFICATION_WINDOW = Duration.ofDays(7);

    private final SupportTicketRepository tickets;
    private final ProductQuestionRepository questions;
    private final CartRepository carts;
    private final NotificationLogRepository notifications;
    private final RefundFailureRepository refundFailures;
    private final OrderService orderService;
    private final Clock clock;
    private final CodCollectionService codCollection;

    public NeedsAttentionService(SupportTicketRepository tickets, ProductQuestionRepository questions,
                                 CartRepository carts, NotificationLogRepository notifications,
                                 RefundFailureRepository refundFailures, OrderService orderService, Clock clock,
                                 CodCollectionService codCollection) {
        this.tickets = tickets;
        this.questions = questions;
        this.carts = carts;
        this.notifications = notifications;
        this.refundFailures = refundFailures;
        this.orderService = orderService;
        this.clock = clock;
        this.codCollection = codCollection;
    }

    public NeedsAttention get() {
        Instant now = clock.instant();
        List<Item> items = new ArrayList<>();

        long openTickets = tickets.countByStatus(SupportTicket.Status.OPEN);
        items.add(new Item("supportTickets", "Support requests waiting on you", (int) openTickets, false, false,
                "Customers who wrote in and have not had a reply yet.", List.of()));

        long unanswered = questions.countByAnswerIsNull();
        items.add(new Item("unansweredQuestions", "Unanswered product questions", (int) unanswered, false, false,
                "Hidden from the storefront until someone answers.", List.of()));

        items.add(flaggedReviews());
        items.add(lowStock());

        List<Cart> stuck = carts.findByStatusAndPaymentDeadlineBeforeOrderByPaymentDeadlineAsc(OrderStatus.PENDING_PAYMENT, now);
        items.add(new Item("stuckPayments", "UPI orders stuck past their payment deadline", stuck.size(), false, false,
                "Still waiting for the buyer after the time allowed; stock stays reserved until they are settled.",
                stuck.stream().map(Cart::getOrderId).limit(MAX_ORDER_IDS).toList()));

        List<NotificationLog> failed = notifications.findByEmailedFalseAndSentAtAfterOrderBySentAtDesc(now.minus(NOTIFICATION_WINDOW));
        Set<Long> failedOrders = new LinkedHashSet<>();
        for (NotificationLog n : failed) {
            if (n.getOrderId() != null) {
                failedOrders.add(n.getOrderId());
            }
        }
        items.add(new Item("failedNotifications", "Notification emails that did not go out (last 7 days)", failed.size(),
                false, false, "No verified email on file, or the mail server could not be reached.",
                failedOrders.stream().limit(MAX_ORDER_IDS).toList()));

        List<com.example.orderservice.entity.RefundFailure> unsettled = refundFailures.findByResolvedAtIsNullOrderByIdAsc();
        items.add(new Item("failedRefunds", "Refunds the system could not make", unsettled.size(), false, false,
                "A checkout failed after the buyer was charged and the automatic refund did not go through - refund by hand, then resolve it.",
                unsettled.stream().map(com.example.orderservice.entity.RefundFailure::getOrderId).distinct().limit(MAX_ORDER_IDS).toList()));

        List<Long> overdueCash = codCollection.overdueOrderIds();
        items.add(new Item("codOverdue", "Delivered cash orders not marked collected", overdueCash.size(), false, false,
                "Delivered more than " + codCollection.overdueDays() + " day(s) ago and still shown as unpaid - collect the cash, then mark them paid.",
                overdueCash.stream().limit(MAX_ORDER_IDS).toList()));

        int total = items.stream().filter(i -> i.count() != null).mapToInt(Item::count).sum();
        return new NeedsAttention(now, total, items);
    }

    private Item flaggedReviews() {
        try {
            int n = orderService.getFlaggedReviews(0, FLAGGED_LIMIT).size();
            return new Item("flaggedReviews", "Reviews customers reported", n, n >= FLAGGED_LIMIT, false,
                    "Hide the ones that break the rules; they stay visible until you do.", List.of());
        } catch (RuntimeException e) {
            log.warn("Needs-attention: flagged reviews unavailable: {}", e.getMessage());
            return unavailable("flaggedReviews", "Reviews customers reported");
        }
    }

    private Item lowStock() {
        try {
            List<LowStockItem> low = orderService.getLowStockReport();
            long out = low.stream().filter(i -> "OUT".equals(i.level())).count();
            return new Item("lowStock", "Products low on stock", low.size(), false, false,
                    out == 0 ? "None are out of stock yet." : out + " out of stock.", List.of());
        } catch (RuntimeException e) {
            log.warn("Needs-attention: low stock unavailable: {}", e.getMessage());
            return unavailable("lowStock", "Products low on stock");
        }
    }

    private static Item unavailable(String key, String title) {
        return new Item(key, title, null, false, true, "ProductService could not be reached - try again shortly.", List.of());
    }
}
