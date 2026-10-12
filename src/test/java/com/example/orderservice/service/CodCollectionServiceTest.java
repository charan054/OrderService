package com.example.orderservice.service;

import com.example.orderservice.dto.CodCollectionReport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodCollectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private CartRepository orders;
    private TrackingEventRepository tracking;
    private CodCollectionService service;

    @BeforeEach
    void setUp() {
        orders = mock(CartRepository.class);
        tracking = mock(TrackingEventRepository.class);
        service = new CodCollectionService(orders, tracking, Clock.fixed(NOW, ZoneOffset.UTC), 2);
    }

    private Cart cash(long id, OrderStatus status, boolean paid, double total) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(9000000000L + id);
        c.setPaymentMethod(PaymentMethod.CASH);
        c.setStatus(status);
        c.setPaid(paid);
        c.setTotalPrice(total);
        return c;
    }

    private void deliveredAt(long id, Instant at) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(id);
        e.setStatus(OrderStatus.DELIVERED);
        e.setTimestamp(at);
        when(tracking.findByOrderIdOrderByTimestampAsc(id)).thenReturn(List.of(e));
    }

    @Test
    void collectedPerDayOwedAndOverdueAreSeparatedAndNetOfRefunds() {
        Cart collectedToday = cash(1, OrderStatus.DELIVERED, true, 200);
        collectedToday.setPaidAt(NOW.minus(1, ChronoUnit.HOURS));
        Cart collectedEarlier = cash(2, OrderStatus.DELIVERED, true, 100);
        collectedEarlier.setPaidAt(NOW.minus(3, ChronoUnit.DAYS));
        collectedEarlier.setRefundedAmount(20);
        Cart tooOld = cash(3, OrderStatus.DELIVERED, true, 999);
        tooOld.setPaidAt(NOW.minus(40, ChronoUnit.DAYS));
        Cart overdue = cash(4, OrderStatus.DELIVERED, false, 150);
        deliveredAt(4, NOW.minus(5, ChronoUnit.DAYS));
        Cart notYetOverdue = cash(5, OrderStatus.DELIVERED, false, 50);
        deliveredAt(5, NOW.minus(1, ChronoUnit.DAYS));
        Cart shipped = cash(6, OrderStatus.SHIPPED, false, 70);
        Cart cancelled = cash(7, OrderStatus.CANCELLED, false, 500);
        Cart online = cash(8, OrderStatus.DELIVERED, false, 300);
        online.setPaymentMethod(PaymentMethod.PHONEPE);
        when(orders.findAll()).thenReturn(List.of(collectedToday, collectedEarlier, tooOld, overdue, notYetOverdue, shipped,
                cancelled, online));

        CodCollectionReport r = service.report("UTC");

        assertEquals(200.0, r.collectedToday());
        assertEquals(14, r.collectedByDay().size());
        assertEquals(80.0, r.collectedByDay().stream().filter(d -> d.date().equals("2026-10-09")).findFirst().orElseThrow().collected());
        assertEquals(270.0, r.outstandingTotal());          // 150 + 50 + 70
        assertEquals(3, r.outstandingOrders());
        assertEquals(1, r.overdue().size());
        assertEquals(4L, r.overdue().get(0).orderId());
        assertEquals(5L, r.overdue().get(0).daysSinceDelivery());
        assertEquals(150.0, r.overdueTotal());
        assertEquals(List.of(4L), service.overdueOrderIds());
    }

    @Test
    void anUnknownTimeZoneIsRejected() {
        assertThrows(ProductException.class, () -> service.report("Mars/Base"));
    }
}
