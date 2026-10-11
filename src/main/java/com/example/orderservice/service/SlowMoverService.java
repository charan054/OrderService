package com.example.orderservice.service;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.SlowMover;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Products that have stock but have not sold in the last N days, biggest tied-up value first. A sale is a unit that
 * was ordered and kept (not cancelled, not returned); an order counts on the day it was placed (its first tracking
 * event). Orders that were cancelled or returned as a whole never count as a sale.
 */
@Service
public class SlowMoverService {
    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 730;
    static final int MAX_ROWS = 200;

    private final OrderService orderService;
    private final CartRepository carts;
    private final TrackingEventRepository tracking;
    private final Clock clock;

    public SlowMoverService(OrderService orderService, CartRepository carts, TrackingEventRepository tracking, Clock clock) {
        this.orderService = orderService;
        this.carts = carts;
        this.tracking = tracking;
        this.clock = clock;
    }

    public List<SlowMover> report(Integer days) {
        int window = days == null ? DEFAULT_DAYS : Math.min(Math.max(days, 1), MAX_DAYS);
        return compute(orderService.getProducts(), carts.findAll(), tracking.findAll(), window, clock.instant());
    }

    static List<SlowMover> compute(List<Product> products, List<Cart> orders, List<TrackingEvent> events, int days, Instant now) {
        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent e : events) {
            if (e.getOrderId() != null && e.getTimestamp() != null) {
                placedAt.merge(e.getOrderId(), e.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Map<Integer, Instant> lastSold = new HashMap<>();
        for (Cart order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.RETURNED
                    || order.getOrderItems() == null) {
                continue;
            }
            Instant when = placedAt.get(order.getOrderId());
            if (when == null) {
                continue;
            }
            for (OrderItem item : order.getOrderItems()) {
                if (item.getOutstandingQuantity() > 0) {
                    lastSold.merge(item.getProductId(), when, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        Instant cutoff = now.minus(Duration.ofDays(days));
        return products.stream()
                .filter(p -> p.getProductStock() > 0)
                .map(p -> {
                    Instant sold = lastSold.get(p.getProductId());
                    return new Object[]{p, sold};
                })
                .filter(pair -> pair[1] == null || ((Instant) pair[1]).isBefore(cutoff))
                .map(pair -> {
                    Product p = (Product) pair[0];
                    Instant sold = (Instant) pair[1];
                    double value = Math.round(p.getProductStock() * p.getProductPrice() * 100.0) / 100.0;
                    return new SlowMover(p.getProductId(), p.getProductName(), p.getProductCategory(), p.getProductStock(),
                            value, sold, sold == null ? null : Duration.between(sold, now).toDays());
                })
                .sorted(Comparator.comparingDouble(SlowMover::stockValue).reversed())
                .limit(MAX_ROWS)
                .toList();
    }
}
