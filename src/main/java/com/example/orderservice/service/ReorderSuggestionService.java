package com.example.orderservice.service;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ReorderSuggestion;
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
 * "Buy N more of X": from how many units of each product sold in the last `days` days (kept units only - cancelled and
 * returned ones do not count; an order is dated by its first tracking event) it works out a daily rate, how many days
 * the current stock lasts at that pace, and how many units to add so it lasts `coverDays`. Only products that would
 * run out sooner than that are listed. This is advice from past sales, not a forecast: a new product, a promotion
 * or a season will not be reflected.
 */
@Service
public class ReorderSuggestionService {
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_COVER_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int MAX_ROWS = 200;

    private final OrderService orderService;
    private final CartRepository carts;
    private final TrackingEventRepository tracking;
    private final Clock clock;

    public ReorderSuggestionService(OrderService orderService, CartRepository carts, TrackingEventRepository tracking, Clock clock) {
        this.orderService = orderService;
        this.carts = carts;
        this.tracking = tracking;
        this.clock = clock;
    }

    public List<ReorderSuggestion> report(Integer days, Integer coverDays) {
        int window = clamp(days, DEFAULT_DAYS);
        int cover = clamp(coverDays, DEFAULT_COVER_DAYS);
        return compute(orderService.getProducts(), carts.findAll(), tracking.findAll(), window, cover, clock.instant());
    }

    private static int clamp(Integer value, int fallback) {
        return value == null ? fallback : Math.min(Math.max(value, 1), MAX_DAYS);
    }

    static List<ReorderSuggestion> compute(List<Product> products, List<Cart> orders, List<TrackingEvent> events,
                                           int days, int coverDays, Instant now) {
        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent e : events) {
            if (e.getOrderId() != null && e.getTimestamp() != null) {
                placedAt.merge(e.getOrderId(), e.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Instant since = now.minus(Duration.ofDays(days));
        Map<Integer, Long> sold = new HashMap<>();
        for (Cart order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.RETURNED
                    || order.getOrderItems() == null) {
                continue;
            }
            Instant when = placedAt.get(order.getOrderId());
            if (when == null || when.isBefore(since) || when.isAfter(now)) {
                continue;
            }
            for (OrderItem item : order.getOrderItems()) {
                if (item.getOutstandingQuantity() > 0) {
                    sold.merge(item.getProductId(), (long) item.getOutstandingQuantity(), Long::sum);
                }
            }
        }
        return products.stream()
                .map(p -> {
                    long units = sold.getOrDefault(p.getProductId(), 0L);
                    if (units == 0) {
                        return null; // nothing sold: not running out because of demand
                    }
                    double rate = units / (double) days;
                    int stock = Math.max(0, p.getProductStock());
                    double daysLeft = stock / rate;
                    if (daysLeft >= coverDays) {
                        return null;
                    }
                    int suggested = (int) Math.ceil(rate * coverDays - stock);
                    return new ReorderSuggestion(p.getProductId(), p.getProductName(), p.getProductCategory(), stock, units,
                            Math.round(rate * 100.0) / 100.0, Math.round(daysLeft * 10.0) / 10.0, Math.max(1, suggested));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(ReorderSuggestion::daysOfStock))
                .limit(MAX_ROWS)
                .toList();
    }
}
