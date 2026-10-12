package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReorderReminderServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final long PHNO = 9876543210L;

    private final List<Cart> orders = new ArrayList<>();
    private final List<TrackingEvent> events = new ArrayList<>();
    private long nextId = 1;

    private void buy(long phno, int productId, String when, OrderStatus status, int qty, int cancelled) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setProductQuantity(qty);
        item.setCancelledQuantity(cancelled);
        Cart c = new Cart();
        c.setOrderId(nextId);
        c.setCustomerPhno(phno);
        c.setStatus(status);
        c.setOrderItems(List.of(item));
        orders.add(c);
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(nextId);
        e.setStatus(OrderStatus.PLACED);
        e.setTimestamp(Instant.parse(when));
        events.add(e);
        nextId++;
    }

    private void buy(long phno, int productId, String when) {
        buy(phno, productId, when, OrderStatus.DELIVERED, 1, 0);
    }

    @Test
    void aProductBoughtEveryThirtyDaysIsDueAboutThirtyDaysAfterTheLastPurchase() {
        buy(PHNO, 7, "2026-08-01T00:00:00Z");
        buy(PHNO, 7, "2026-08-31T00:00:00Z");
        buy(PHNO, 7, "2026-09-10T00:00:00Z"); // gaps 30 and 10 -> usual 20 days; 31 days since the last -> due

        List<ReorderReminderService.Due> due = ReorderReminderService.findDue(orders, events, NOW);

        assertEquals(1, due.size());
        assertEquals(7, due.get(0).productId());
        assertEquals(20.0, due.get(0).usualGapDays(), 0.01);
    }

    @Test
    void notYetDueAndLongAbandonedAreBothLeftAlone() {
        buy(PHNO, 1, "2026-09-01T00:00:00Z");
        buy(PHNO, 1, "2026-10-01T00:00:00Z"); // usual 30 days, only 10 since the last -> too early
        buy(PHNO, 2, "2026-03-01T00:00:00Z");
        buy(PHNO, 2, "2026-03-31T00:00:00Z"); // usual 30 days, 194 since the last -> given up

        assertTrue(ReorderReminderService.findDue(orders, events, NOW).isEmpty());
    }

    @Test
    void aSinglePurchaseOrTooFrequentRepeatsNeverQualify() {
        buy(PHNO, 1, "2026-08-01T00:00:00Z");
        buy(PHNO, 2, "2026-09-01T00:00:00Z");
        buy(PHNO, 2, "2026-09-02T00:00:00Z"); // one day apart -> not a stocking-up habit

        assertTrue(ReorderReminderService.findDue(orders, events, NOW).isEmpty());
    }

    @Test
    void cancelledReturnedAndFullyCancelledLinesDoNotCountAsPurchases() {
        buy(PHNO, 3, "2026-08-01T00:00:00Z");
        buy(PHNO, 3, "2026-08-31T00:00:00Z", OrderStatus.CANCELLED, 1, 0);
        buy(PHNO, 3, "2026-09-10T00:00:00Z", OrderStatus.RETURNED, 1, 0);
        buy(PHNO, 3, "2026-09-12T00:00:00Z", OrderStatus.PLACED, 2, 2);

        assertTrue(ReorderReminderService.findDue(orders, events, NOW).isEmpty()); // only one real purchase left
    }

    @Test
    void customersAreKeptApartAndListedByPhone() {
        buy(9000000002L, 5, "2026-08-01T00:00:00Z");
        buy(9000000002L, 5, "2026-08-31T00:00:00Z");
        buy(9000000001L, 5, "2026-08-01T00:00:00Z");
        buy(9000000001L, 5, "2026-08-31T00:00:00Z");

        List<ReorderReminderService.Due> due = ReorderReminderService.findDue(orders, events, NOW);

        assertEquals(List.of(9000000001L, 9000000002L), due.stream().map(ReorderReminderService.Due::phno).toList());
    }
}
