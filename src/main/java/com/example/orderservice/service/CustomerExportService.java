package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Admin customer list as CSV, one row per customer who has an order that still stands (PLACED / SHIPPED / DELIVERED,
 * the same definition CustomerInsightsService uses), for mailing lists and follow-up. Each row carries two segment
 * labels: NEW (exactly one order) or REPEAT (two or more), and whether the customer is dormant, meaning their latest
 * order is older than dormantDays. The optional segment filter is one of all, new, repeat, dormant or active
 * (= not dormant). The verified email and whether the customer still accepts promotional email are included, so the
 * file can be used without mailing people who unsubscribed (see EmailPreferenceService).
 *
 * An order with no tracking event (placed before tracking existed) has no date; a customer whose orders are all
 * undated is never called dormant, since there is nothing to measure.
 */
@Service
public class CustomerExportService {
    static final int DEFAULT_DORMANT_DAYS = 90;
    private static final Set<OrderStatus> STANDING = Set.of(OrderStatus.PLACED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    private static final Set<String> SEGMENTS = Set.of("all", "new", "repeat", "dormant", "active");

    private final CartRepository orders;
    private final TrackingEventRepository trackingEvents;
    private final CustomerAccountRepository accounts;
    private final Clock clock;

    public CustomerExportService(CartRepository orders, TrackingEventRepository trackingEvents,
                                 CustomerAccountRepository accounts, Clock clock) {
        this.orders = orders;
        this.trackingEvents = trackingEvents;
        this.accounts = accounts;
        this.clock = clock;
    }

    private static final class Tally {
        String name;
        long newestOrderId = Long.MIN_VALUE;
        int orders;
        double net;
        Instant first;
        Instant last;
    }

    public String exportCsv(String segment, int dormantDays) {
        String wanted = segment == null || segment.isBlank() ? "all" : segment.trim().toLowerCase(Locale.ROOT);
        if (!SEGMENTS.contains(wanted)) {
            throw new ProductException("segment must be one of: all, new, repeat, dormant, active");
        }
        if (dormantDays < 1 || dormantDays > 3650) {
            throw new ProductException("dormantDays must be between 1 and 3650");
        }
        Instant now = clock.instant();
        Instant dormantBefore = now.minus(Duration.ofDays(dormantDays));

        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent event : trackingEvents.findAll()) {
            if (event.getOrderId() != null && event.getTimestamp() != null) {
                placedAt.merge(event.getOrderId(), event.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Map<Long, CustomerAccount> accountByPhno = new HashMap<>();
        for (CustomerAccount account : accounts.findAll()) {
            accountByPhno.put(account.getPhno(), account);
        }

        Map<Long, Tally> byCustomer = new TreeMap<>();
        for (Cart order : orders.findAll()) {
            // Set.of(...).contains(null) throws, and some old rows predate the status column.
            if (order.getStatus() == null || !STANDING.contains(order.getStatus())) {
                continue;
            }
            Tally t = byCustomer.computeIfAbsent(order.getCustomerPhno(), k -> new Tally());
            t.orders++;
            t.net += Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            long id = order.getOrderId() == null ? Long.MIN_VALUE + 1 : order.getOrderId();
            if (id >= t.newestOrderId) {
                t.newestOrderId = id;
                t.name = order.getCustomerName();
            }
            Instant when = order.getOrderId() == null ? null : placedAt.get(order.getOrderId());
            if (when != null) {
                if (t.first == null || when.isBefore(t.first)) {
                    t.first = when;
                }
                if (t.last == null || when.isAfter(t.last)) {
                    t.last = when;
                }
            }
        }

        StringBuilder csv = new StringBuilder("customerPhno,customerName,email,marketingEmails,segment,dormant,orders,"
                + "netSpend,firstOrderAt,lastOrderAt,daysSinceLastOrder\r\n");
        for (Map.Entry<Long, Tally> entry : byCustomer.entrySet()) {
            Tally t = entry.getValue();
            boolean repeat = t.orders >= 2;
            boolean dormant = t.last != null && t.last.isBefore(dormantBefore);
            boolean include = switch (wanted) {
                case "new" -> !repeat;
                case "repeat" -> repeat;
                case "dormant" -> dormant;
                case "active" -> !dormant;
                default -> true;
            };
            if (!include) {
                continue;
            }
            CustomerAccount account = accountByPhno.get(entry.getKey());
            String email = account == null ? null : account.getEmail();
            csv.append(entry.getKey()).append(',')
                    .append(OrderService.csvCell(t.name)).append(',')
                    .append(OrderService.csvCell(email)).append(',')
                    .append(account == null || email == null || email.isBlank() ? "" : String.valueOf(!account.isMarketingOptOut()))
                    .append(',')
                    .append(repeat ? "REPEAT" : "NEW").append(',')
                    .append(dormant).append(',')
                    .append(t.orders).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", t.net)).append(',')
                    .append(t.first == null ? "" : t.first).append(',')
                    .append(t.last == null ? "" : t.last).append(',')
                    .append(t.last == null ? "" : Duration.between(t.last, now).toDays()).append("\r\n");
        }
        return csv.toString();
    }
}
