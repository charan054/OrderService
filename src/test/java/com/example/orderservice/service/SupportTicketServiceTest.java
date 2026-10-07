package com.example.orderservice.service;

import com.example.orderservice.dto.NewSupportTicket;
import com.example.orderservice.dto.SupportTicketView;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.SupportMessage;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.SupportMessageRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Mock
    private SupportTicketRepository tickets;
    @Mock
    private SupportMessageRepository messages;
    @Mock
    private CartRepository orders;
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private MailService mailService;

    private SupportTicketService service;
    private final List<SupportMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SupportTicketService(tickets, messages, orders, accounts, mailService, Clock.fixed(NOW, ZoneOffset.UTC), "");
        lenient().when(tickets.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(7L);
            }
            return t;
        });
        lenient().when(messages.save(any(SupportMessage.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().when(messages.findByTicketIdOrderByIdAsc(anyLong())).thenAnswer(inv -> List.copyOf(saved));
    }

    private Cart order(long id, OrderStatus status, int... productIds) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(PHNO);
        c.setStatus(status);
        c.setPaymentMethod(PaymentMethod.CASH);
        List<OrderItem> items = new ArrayList<>();
        for (int pid : productIds) {
            OrderItem i = new OrderItem();
            i.setProductId(pid);
            i.setProductQuantity(1);
            items.add(i);
        }
        c.setOrderItems(items);
        lenient().when(orders.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private SupportTicket existing(SupportTicket.Status status) {
        SupportTicket t = new SupportTicket();
        t.setId(7L);
        t.setCustomerPhno(PHNO);
        t.setOrderId(42L);
        t.setCategory(SupportTicket.Category.DAMAGED);
        t.setStatus(status);
        when(tickets.findById(7L)).thenReturn(Optional.of(t));
        return t;
    }

    private void verifiedEmail() {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(PHNO);
        a.setEmail("asha@example.com");
        when(accounts.findById(PHNO)).thenReturn(Optional.of(a));
    }

    @Test
    void opensARequestWithTheFirstMessage() {
        order(42L, OrderStatus.DELIVERED, 1, 2);

        SupportTicketView v = service.open(new NewSupportTicket(PHNO, 42L, "damaged", 2, "  The jar was cracked  ",
                "https://img.example/photo.jpg"));

        assertEquals(SupportTicket.Category.DAMAGED, v.ticket().getCategory());
        assertEquals(SupportTicket.Status.OPEN, v.ticket().getStatus());
        assertEquals(2, v.ticket().getProductId());
        assertEquals("https://img.example/photo.jpg", v.ticket().getPhotoUrl());
        assertEquals(1, v.messages().size());
        assertEquals("The jar was cracked", v.messages().get(0).getBody());
        assertEquals(SupportMessage.Author.CUSTOMER, v.messages().get(0).getAuthor());
        assertEquals("DELIVERED", v.order().status());
        verifyNoInteractions(mailService); // no staff address configured
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        Cart other = order(42L, OrderStatus.DELIVERED, 1);
        other.setCustomerPhno(9000000001L);

        assertThrows(OrderNotFoundException.class,
                () -> service.open(new NewSupportTicket(PHNO, 42L, "DAMAGED", null, "Broken item", null)));
        verify(tickets, never()).save(any());
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsSaved() {
        order(42L, OrderStatus.DELIVERED, 1);
        order(43L, OrderStatus.PENDING_PAYMENT, 1);

        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 42L, "LATE", null, "Where is it", null)));
        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 42L, "OTHER", null, "hey", null)));
        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 42L, "OTHER", null, "x".repeat(1001), null)));
        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 42L, "OTHER", null, "Bad photo link", "javascript:alert(1)")));
        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 42L, "WRONG_ITEM", 99, "Not in my order", null)));
        assertThrows(ProductException.class, () -> service.open(new NewSupportTicket(PHNO, 43L, "PAYMENT", null, "Payment stuck", null)));
        verify(tickets, never()).save(any());
    }

    @Test
    void onlyOneUnresolvedRequestPerOrderAndAFewPerCustomer() {
        order(42L, OrderStatus.DELIVERED, 1);
        SupportTicket open = new SupportTicket();
        open.setId(5L);
        when(tickets.findByOrderIdAndStatusIn(eq(42L), any())).thenReturn(List.of(open));

        ProductException perOrder = assertThrows(ProductException.class,
                () -> service.open(new NewSupportTicket(PHNO, 42L, "OTHER", null, "Another problem", null)));
        assertTrue(perOrder.getMessage().contains("#5"));

        order(44L, OrderStatus.DELIVERED, 1);
        when(tickets.findByOrderIdAndStatusIn(eq(44L), any())).thenReturn(List.of());
        when(tickets.countByCustomerPhnoAndStatusIn(eq(PHNO), any())).thenReturn(5L);
        assertThrows(ProductException.class,
                () -> service.open(new NewSupportTicket(PHNO, 44L, "OTHER", null, "Yet another one", null)));
    }

    @Test
    void aStaffReplyIsRecordedAndEmailedToTheCustomer() {
        existing(SupportTicket.Status.OPEN);
        verifiedEmail();

        SupportTicketView v = service.staffReply(7L, "Sorry! We'll send a replacement.");

        assertEquals(SupportTicket.Status.ANSWERED, v.ticket().getStatus());
        assertEquals(SupportMessage.Author.STAFF, saved.get(0).getAuthor());
        verify(mailService).send(eq("asha@example.com"), eq("Reply to your support request #7"),
                contains("Sorry! We'll send a replacement."));
    }

    @Test
    void aCustomerWithoutAnEmailStillGetsTheReplyOnTheirAccount() {
        existing(SupportTicket.Status.OPEN);
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        service.staffReply(7L, "Looking into it now");

        verify(messages).save(any(SupportMessage.class));
        verifyNoInteractions(mailService);
    }

    @Test
    void resolvingEmailsTheNoteAndCannotHappenTwice() {
        SupportTicket t = existing(SupportTicket.Status.ANSWERED);
        verifiedEmail();

        service.resolve(7L, "  Refunded the damaged item  ");

        assertEquals(SupportTicket.Status.RESOLVED, t.getStatus());
        assertEquals("Refunded the damaged item", t.getResolutionNote());
        assertEquals(NOW, t.getResolvedAt());
        verify(mailService).send(eq("asha@example.com"), eq("Your support request #7 is resolved"), contains("Refunded the damaged item"));
        assertThrows(ProductException.class, () -> service.resolve(7L, null));
    }

    @Test
    void aCustomerReplyReopensARecentlyResolvedRequest() {
        SupportTicket t = existing(SupportTicket.Status.RESOLVED);
        t.setResolvedAt(NOW.minus(3, ChronoUnit.DAYS));
        t.setResolutionNote("Refunded");

        service.customerReply(7L, PHNO, "Still missing one item");

        assertEquals(SupportTicket.Status.OPEN, t.getStatus());
        assertNull(t.getResolvedAt());
        assertNull(t.getResolutionNote());
    }

    @Test
    void anOldResolvedRequestCannotBeReopened() {
        SupportTicket t = existing(SupportTicket.Status.RESOLVED);
        t.setResolvedAt(NOW.minus(31, ChronoUnit.DAYS));

        assertThrows(ProductException.class, () -> service.customerReply(7L, PHNO, "Something else now"));
        verify(messages, never()).save(any());
    }

    @Test
    void aCustomerCannotReplyToSomeoneElsesRequest() {
        existing(SupportTicket.Status.OPEN);

        assertThrows(OrderNotFoundException.class, () -> service.customerReply(7L, 9000000001L, "Let me in please"));
    }

    @Test
    void theStaffAddressIsToldAboutNewRequestsWhenConfigured() {
        service = new SupportTicketService(tickets, messages, orders, accounts, mailService, Clock.fixed(NOW, ZoneOffset.UTC), "owner@example.com");
        order(42L, OrderStatus.DELIVERED, 1);

        service.open(new NewSupportTicket(PHNO, 42L, "MISSING_ITEM", null, "One packet missing", null));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("owner@example.com"), subject.capture(), anyString());
        assertEquals("New support request #7 (MISSING_ITEM, order #42)", subject.getValue());
    }

    @Test
    void theQueueShowsWaitingOnUsFirst() {
        SupportTicket answered = new SupportTicket();
        answered.setId(1L);
        answered.setStatus(SupportTicket.Status.ANSWERED);
        SupportTicket open = new SupportTicket();
        open.setId(2L);
        open.setStatus(SupportTicket.Status.OPEN);
        when(tickets.findByStatusInOrderByUpdatedAtAsc(any())).thenReturn(List.of(answered, open));

        assertEquals(List.of(2L, 1L), service.queue(null).stream().map(SupportTicket::getId).toList());
        assertThrows(ProductException.class, () -> service.queue("LOST"));
    }
}
