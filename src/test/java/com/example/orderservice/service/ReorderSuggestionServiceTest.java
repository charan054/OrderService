package com.example.orderservice.service;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ReorderSuggestion;
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

class ReorderSuggestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private final List<Cart> orders = new ArrayList<>();
    private final List<TrackingEvent> events = new ArrayList<>();
    private long nextId = 1;

    private static Product product(int id, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("P" + id);
        p.setProductStock(stock);
        return p;
    }

    private void sell(int productId, int qty, String when, OrderStatus status, int cancelled) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setProductQuantity(qty);
        item.setCancelledQuantity(cancelled);
        Cart c = new Cart();
        c.setOrderId(nextId);
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

    @Test
    void suggestsEnoughUnitsToCoverTheRequestedDaysFastestRunOutFirst() {
        sell(1, 30, "2026-10-01T00:00:00Z", OrderStatus.DELIVERED, 0); // 1/day; 10 in stock -> 10 days left
        sell(2, 60, "2026-09-20T00:00:00Z", OrderStatus.DELIVERED, 0); // 2/day; 20 in stock -> 10 days left
        sell(3, 3, "2026-10-05T00:00:00Z", OrderStatus.DELIVERED, 0);  // 0.1/day; 100 in stock -> plenty

        List<ReorderSuggestion> s = ReorderSuggestionService.compute(
                List.of(product(1, 10), product(2, 20), product(3, 100)), orders, events, 30, 30, NOW);

        assertEquals(2, s.size());
        assertEquals(10.0, s.get(0).daysOfStock());
        assertEquals(20, s.get(0).suggestedQuantity()); // 1/day x 30 days = 30, minus 10 in stock
        assertEquals(40, s.get(1).suggestedQuantity()); // 2/day x 30 = 60, minus 20
    }

    @Test
    void soldOutProductsThatSoldRecentlyAreTopOfTheListAndSalesOutsideTheWindowAreIgnored() {
        sell(1, 15, "2026-10-08T00:00:00Z", OrderStatus.DELIVERED, 0);
        sell(2, 500, "2026-06-01T00:00:00Z", OrderStatus.DELIVERED, 0); // long ago

        List<ReorderSuggestion> s = ReorderSuggestionService.compute(List.of(product(1, 0), product(2, 0)), orders, events, 30, 30, NOW);

        assertEquals(1, s.size());
        assertEquals(0.0, s.get(0).daysOfStock());
        assertEquals(15, s.get(0).suggestedQuantity());
    }

    @Test
    void cancelledReturnedAndCancelledUnitsAreNotSales() {
        sell(1, 30, "2026-10-01T00:00:00Z", OrderStatus.CANCELLED, 0);
        sell(1, 30, "2026-10-02T00:00:00Z", OrderStatus.RETURNED, 0);
        sell(1, 30, "2026-10-03T00:00:00Z", OrderStatus.PLACED, 30);

        assertTrue(ReorderSuggestionService.compute(List.of(product(1, 0)), orders, events, 30, 30, NOW).isEmpty());
    }
}
