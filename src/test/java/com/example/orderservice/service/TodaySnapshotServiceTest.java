package com.example.orderservice.service;

import com.example.orderservice.dto.TodaySnapshot;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodaySnapshotServiceTest {
    // 2026-10-07 12:00 UTC
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private CartRepository orders;
    @Mock
    private TrackingEventRepository tracking;
    @Mock
    private ProductQuestionRepository questions;

    private TodaySnapshotService service;

    @BeforeEach
    void setUp() {
        service = new TodaySnapshotService(orders, tracking, questions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Cart order(long id, OrderStatus status, PaymentMethod method, boolean paid, double total, double refunded) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setStatus(status);
        c.setPaymentMethod(method);
        c.setPaid(paid);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        return c;
    }

    private TrackingEvent event(long orderId, OrderStatus status, String at) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(status);
        e.setTimestamp(Instant.parse(at));
        return e;
    }

    @Test
    void countsTodaysOrdersRevenueDeliveriesAndCancellations() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, OrderStatus.PLACED, PaymentMethod.CASH, false, 500, 100),     // placed today
                order(2, OrderStatus.DELIVERED, PaymentMethod.PHONEPE, true, 300, 0),  // placed today, delivered today
                order(3, OrderStatus.CANCELLED, PaymentMethod.CASH, false, 999, 999),  // placed + cancelled today
                order(4, OrderStatus.DELIVERED, PaymentMethod.PHONEPE, true, 700, 0))); // placed yesterday
        when(tracking.findAll()).thenReturn(List.of(
                event(1, OrderStatus.PLACED, "2026-10-07T08:00:00Z"),
                event(2, OrderStatus.PLACED, "2026-10-07T07:00:00Z"),
                event(2, OrderStatus.DELIVERED, "2026-10-07T11:00:00Z"),
                event(3, OrderStatus.PLACED, "2026-10-07T06:00:00Z"),
                event(3, OrderStatus.CANCELLED, "2026-10-07T07:00:00Z"),
                event(4, OrderStatus.PLACED, "2026-10-06T10:00:00Z"),
                event(4, OrderStatus.DELIVERED, "2026-10-06T20:00:00Z")));
        when(questions.findByAnswerIsNullOrderByIdAsc()).thenReturn(List.of(new ProductQuestion(), new ProductQuestion()));

        TodaySnapshot s = service.snapshot(null, 24);

        assertEquals(LocalDate.of(2026, 10, 7), s.date());
        assertEquals(2, s.ordersPlaced());
        assertEquals(700.0, s.netRevenue());
        assertEquals(1, s.delivered());
        assertEquals(1, s.cancelled());
        assertEquals(2, s.attention().unansweredQuestions());
    }

    @Test
    void flagsStaleUnshippedOrdersOldestFirstAndUnpaidCashDeliveries() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, OrderStatus.PLACED, PaymentMethod.CASH, false, 100, 0),    // placed 30h ago -> stale
                order(2, OrderStatus.PLACED, PaymentMethod.CASH, false, 100, 0),    // placed 2h ago -> fine
                order(3, OrderStatus.PLACED, PaymentMethod.CASH, false, 100, 0),    // placed 50h ago -> stale, older
                order(4, OrderStatus.DELIVERED, PaymentMethod.CASH, false, 100, 0), // cash not collected
                order(5, OrderStatus.DELIVERED, PaymentMethod.CASH, true, 100, 0),  // collected
                order(6, OrderStatus.PENDING_PAYMENT, PaymentMethod.PHONEPE, false, 100, 0)));
        when(tracking.findAll()).thenReturn(List.of(
                event(1, OrderStatus.PLACED, "2026-10-06T06:00:00Z"),
                event(2, OrderStatus.PLACED, "2026-10-07T10:00:00Z"),
                event(3, OrderStatus.PLACED, "2026-10-05T10:00:00Z")));
        when(questions.findByAnswerIsNullOrderByIdAsc()).thenReturn(List.of());

        TodaySnapshot.Attention a = service.snapshot("UTC", 24).attention();

        assertEquals(List.of(3L, 1L), a.unshipped());
        assertEquals(List.of(4L), a.cashDeliveredUnpaid());
        assertEquals(1, a.pendingPayments());
    }

    @Test
    void usesTheRequestedTimeZoneForWhatDayItIs() {
        // 12:00 UTC is 17:30 in Kolkata, still Oct 7; an order at 19:00 UTC on Oct 6 is 00:30 Oct 7 there.
        when(orders.findAll()).thenReturn(List.of(order(1, OrderStatus.PLACED, PaymentMethod.CASH, false, 100, 0)));
        when(tracking.findAll()).thenReturn(List.of(event(1, OrderStatus.PLACED, "2026-10-06T19:00:00Z")));
        when(questions.findByAnswerIsNullOrderByIdAsc()).thenReturn(List.of());

        assertEquals(0, service.snapshot("UTC", 24).ordersPlaced());
        assertEquals(1, service.snapshot("Asia/Kolkata", 24).ordersPlaced());
    }

    @Test
    void rowsWithNoStatusAreIgnoredAndBadArgumentsRejected() {
        when(orders.findAll()).thenReturn(List.of(order(1, null, PaymentMethod.CASH, false, 100, 0)));
        when(tracking.findAll()).thenReturn(List.of());
        when(questions.findByAnswerIsNullOrderByIdAsc()).thenReturn(List.of());

        assertEquals(0, service.snapshot(null, 24).ordersPlaced());
        assertThrows(ProductException.class, () -> service.snapshot("Mars/Base", 24));
        assertThrows(ProductException.class, () -> service.snapshot(null, 0));
        assertThrows(ProductException.class, () -> service.snapshot(null, 721));
    }
}
