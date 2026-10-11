package com.example.orderservice.service;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.SlowMover;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlowMoverServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private static Product product(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("P" + id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    private static Cart order(long id, OrderStatus status, int productId, int qty, int cancelled) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setProductQuantity(qty);
        item.setCancelledQuantity(cancelled);
        Cart c = new Cart();
        c.setOrderId(id);
        c.setStatus(status);
        c.setOrderItems(List.of(item));
        return c;
    }

    private static TrackingEvent placed(long orderId, String when) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(OrderStatus.PLACED);
        e.setTimestamp(Instant.parse(when));
        return e;
    }

    @Test
    void listsStockedProductsWithNoRecentKeptSaleBiggestValueFirst() {
        List<Product> products = List.of(product(1, 10, 5), product(2, 100, 3), product(3, 50, 2), product(4, 5, 0), product(5, 20, 4));
        List<Cart> orders = List.of(
                order(1, OrderStatus.PLACED, 1, 1, 0),       // sold 2 days ago -> not slow
                order(2, OrderStatus.DELIVERED, 2, 1, 0),    // sold 60 days ago -> slow
                order(3, OrderStatus.CANCELLED, 3, 1, 0),    // whole order cancelled -> never sold
                order(4, OrderStatus.PLACED, 5, 2, 2));      // every unit cancelled -> never sold
        List<TrackingEvent> events = List.of(placed(1, "2026-10-09T00:00:00Z"), placed(2, "2026-08-12T00:00:00Z"),
                placed(3, "2026-10-10T00:00:00Z"), placed(4, "2026-10-10T00:00:00Z"));

        List<SlowMover> slow = SlowMoverService.compute(products, orders, events, 30, NOW);

        assertEquals(List.of(2, 3, 5), slow.stream().map(SlowMover::productId).toList()); // 300, 100, 80; out of stock (4) and fresh (1) left out
        assertEquals(60L, slow.get(0).daysSinceLastSale());
        assertNull(slow.get(1).lastSoldAt());
        assertNull(slow.get(1).daysSinceLastSale());
        assertEquals(300.0, slow.get(0).stockValue());
    }

    @Test
    void theLatestKeptSaleDecidesAndAnEarlierEventTimeIsTheOrderDate() {
        List<Cart> orders = List.of(order(1, OrderStatus.DELIVERED, 1, 1, 0), order(2, OrderStatus.DELIVERED, 1, 1, 0));
        List<TrackingEvent> events = List.of(placed(1, "2026-07-01T00:00:00Z"), placed(2, "2026-10-01T00:00:00Z"),
                placed(2, "2026-10-05T00:00:00Z"));

        assertEquals(0, SlowMoverService.compute(List.of(product(1, 10, 5)), orders, events, 30, NOW).size());
        assertEquals(1, SlowMoverService.compute(List.of(product(1, 10, 5)), orders, events, 7, NOW).size());
    }
}
