package com.example.orderservice.service;

import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.ModerationReview;
import com.example.orderservice.dto.NeedsAttention;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeedsAttentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private SupportTicketRepository tickets;
    private ProductQuestionRepository questions;
    private CartRepository carts;
    private NotificationLogRepository notifications;
    private OrderService orderService;
    private NeedsAttentionService service;

    @BeforeEach
    void setUp() {
        tickets = mock(SupportTicketRepository.class);
        questions = mock(ProductQuestionRepository.class);
        carts = mock(CartRepository.class);
        notifications = mock(NotificationLogRepository.class);
        orderService = mock(OrderService.class);
        service = new NeedsAttentionService(tickets, questions, carts, notifications, orderService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(carts.findByStatusAndPaymentDeadlineBeforeOrderByPaymentDeadlineAsc(any(), any())).thenReturn(List.of());
        when(notifications.findByEmailedFalseAndSentAtAfterOrderBySentAtDesc(any())).thenReturn(List.of());
        when(orderService.getFlaggedReviews(anyInt(), anyInt())).thenReturn(List.of());
        when(orderService.getLowStockReport()).thenReturn(List.of());
    }

    private static NeedsAttention.Item item(NeedsAttention result, String key) {
        return result.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private static Cart cart(long id) {
        Cart c = new Cart();
        c.setOrderId(id);
        return c;
    }

    private static NotificationLog notification(Long orderId) {
        NotificationLog n = new NotificationLog();
        n.setOrderId(orderId);
        return n;
    }

    @Test
    void everythingQuietIsAllClearWithSixRowsInOrder() {
        NeedsAttention result = service.get();

        assertEquals(0, result.total());
        assertEquals(NOW, result.generatedAt());
        assertEquals(List.of("supportTickets", "unansweredQuestions", "flaggedReviews", "lowStock", "stuckPayments",
                "failedNotifications"), result.items().stream().map(NeedsAttention.Item::key).toList());
        assertTrue(result.items().stream().allMatch(i -> i.count() == 0 && !i.unavailable()));
    }

    @Test
    void eachRowCountsItsOwnThingAndTotalAddsThemUp() {
        when(tickets.countByStatus(SupportTicket.Status.OPEN)).thenReturn(3L);
        when(questions.countByAnswerIsNull()).thenReturn(2L);
        when(orderService.getFlaggedReviews(anyInt(), anyInt())).thenReturn(List.of(mock(ModerationReview.class)));
        when(orderService.getLowStockReport()).thenReturn(List.of(
                new LowStockItem(1, "Pen", 0, 5, "OUT", 0), new LowStockItem(2, "Ink", 3, 5, "LOW", 1)));
        when(carts.findByStatusAndPaymentDeadlineBeforeOrderByPaymentDeadlineAsc(OrderStatus.PENDING_PAYMENT, NOW))
                .thenReturn(List.of(cart(41), cart(42)));
        when(notifications.findByEmailedFalseAndSentAtAfterOrderBySentAtDesc(any()))
                .thenReturn(List.of(notification(7L), notification(7L), notification(9L)));

        NeedsAttention result = service.get();

        assertEquals(3, item(result, "supportTickets").count());
        assertEquals(2, item(result, "unansweredQuestions").count());
        assertEquals(1, item(result, "flaggedReviews").count());
        assertEquals(2, item(result, "lowStock").count());
        assertEquals("1 out of stock.", item(result, "lowStock").detail());
        assertEquals(2, item(result, "stuckPayments").count());
        assertEquals(List.of(41L, 42L), item(result, "stuckPayments").orderIds());
        assertEquals(3, item(result, "failedNotifications").count());
        assertEquals(List.of(7L, 9L), item(result, "failedNotifications").orderIds());
        assertEquals(3 + 2 + 1 + 2 + 2 + 3, result.total());
    }

    @Test
    void failedNotificationsLookBackSevenDaysAndSkipOnesWithNoOrder() {
        when(notifications.findByEmailedFalseAndSentAtAfterOrderBySentAtDesc(any()))
                .thenReturn(List.of(notification(null), notification(5L)));

        NeedsAttention result = service.get();

        verify(notifications).findByEmailedFalseAndSentAtAfterOrderBySentAtDesc(NOW.minusSeconds(7 * 24 * 3600));
        assertEquals(2, item(result, "failedNotifications").count());
        assertEquals(List.of(5L), item(result, "failedNotifications").orderIds());
    }

    @Test
    void orderIdListsAreCappedAtTenButTheCountIsNot() {
        when(carts.findByStatusAndPaymentDeadlineBeforeOrderByPaymentDeadlineAsc(any(), any()))
                .thenReturn(LongStream.rangeClosed(1, 25).mapToObj(NeedsAttentionServiceTest::cart).toList());

        NeedsAttention.Item stuck = item(service.get(), "stuckPayments");

        assertEquals(25, stuck.count());
        assertEquals(10, stuck.orderIds().size());
        assertEquals(1L, stuck.orderIds().get(0));
    }

    @Test
    void aFullPageOfFlaggedReviewsIsShownAsAtLeast() {
        when(orderService.getFlaggedReviews(anyInt(), anyInt())).thenReturn(
                IntStream.range(0, NeedsAttentionService.FLAGGED_LIMIT).mapToObj(i -> mock(ModerationReview.class)).toList());

        NeedsAttention.Item flagged = item(service.get(), "flaggedReviews");

        assertEquals(NeedsAttentionService.FLAGGED_LIMIT, flagged.count());
        assertTrue(flagged.more());
    }

    @Test
    void whenProductServiceIsDownThoseRowsSayUnavailableAndAreNotCountedAsZero() {
        when(tickets.countByStatus(SupportTicket.Status.OPEN)).thenReturn(4L);
        when(orderService.getFlaggedReviews(anyInt(), anyInt())).thenThrow(new RuntimeException("connection refused"));
        when(orderService.getLowStockReport()).thenThrow(new RuntimeException("connection refused"));

        NeedsAttention result = service.get();

        for (String key : List.of("flaggedReviews", "lowStock")) {
            NeedsAttention.Item i = item(result, key);
            assertTrue(i.unavailable());
            assertNull(i.count());
            assertFalse(i.more());
        }
        // The rows that do not depend on ProductService are still there, and total ignores the unknown ones.
        assertEquals(4, item(result, "supportTickets").count());
        assertEquals(4, result.total());
    }
}
