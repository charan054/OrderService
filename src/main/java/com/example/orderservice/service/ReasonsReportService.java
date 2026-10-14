package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ReasonsReport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The "why do orders come back" report. Orders are dated by their first tracking event (when they were placed) and
 * grouped by that date's week. A whole-order cancel or return counts every unit in it (the order lines are zeroed out
 * by the cancel/return itself; a line of an older order with neither count set falls back to the order's status).
 */
@Service
public class ReasonsReportService {
    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 730;
    static final int MAX_PRODUCT_ROWS = 20;
    static final int MAX_REASON_LENGTH = 60;
    static final String NOT_GIVEN = "not given";

    private final CartRepository carts;
    private final TrackingEventRepository tracking;
    private final ProductClient productClient;
    private final Clock clock;

    public ReasonsReportService(CartRepository carts, TrackingEventRepository tracking, ProductClient productClient, Clock clock) {
        this.carts = carts;
        this.tracking = tracking;
        this.productClient = productClient;
        this.clock = clock;
    }

    public ReasonsReport report(LocalDate from, LocalDate to) {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        if (end.isBefore(start)) {
            throw new ProductException("'to' date is before 'from' date");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new ProductException("Choose a range of at most " + MAX_DAYS + " days");
        }
        Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent e : tracking.findAll()) {
            if (e.getOrderId() != null && e.getTimestamp() != null) {
                placedAt.merge(e.getOrderId(), e.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        List<Cart> inRange = new ArrayList<>();
        for (Cart order : carts.findAll()) {
            Instant when = placedAt.get(order.getOrderId());
            if (when != null && !when.isBefore(fromInstant) && when.isBefore(toExclusive) && order.getOrderItems() != null) {
                inRange.add(order);
            }
        }
        return build(start, end, inRange, placedAt);
    }

    ReasonsReport build(LocalDate from, LocalDate to, List<Cart> orders, Map<Long, Instant> placedAt) {
        Map<String, Integer> cancelReasons = new HashMap<>();
        Map<String, Integer> returnReasons = new HashMap<>();
        Map<LocalDate, int[]> weeks = new TreeMap<>(); // placed, cancelled, returned
        Map<Integer, int[]> products = new HashMap<>(); // ordered, cancelled, returned
        int withCancel = 0, withReturn = 0, unitsCancelled = 0, unitsReturned = 0;

        for (Cart order : orders) {
            LocalDate week = placedAt.get(order.getOrderId()).atZone(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int[] w = weeks.computeIfAbsent(week, k -> new int[3]);
            w[0]++;
            boolean anyCancel = false, anyReturn = false;
            for (OrderItem item : order.getOrderItems()) {
                int cancelled = item.getCancelledQuantity();
                int returned = item.getReturnedQuantity();
                if (cancelled + returned == 0) {
                    // Older rows: the whole order was cancelled or returned without the lines being zeroed.
                    if (order.getStatus() == OrderStatus.CANCELLED) cancelled = item.getProductQuantity();
                    else if (order.getStatus() == OrderStatus.RETURNED) returned = item.getProductQuantity();
                }
                int[] p = products.computeIfAbsent(item.getProductId(), k -> new int[3]);
                p[0] += item.getProductQuantity();
                p[1] += cancelled;
                p[2] += returned;
                if (cancelled > 0) {
                    anyCancel = true;
                    unitsCancelled += cancelled;
                    w[1] += cancelled;
                    cancelReasons.merge(cancelReasonOf(order), cancelled, Integer::sum);
                }
                if (returned > 0) {
                    anyReturn = true;
                    unitsReturned += returned;
                    w[2] += returned;
                    returnReasons.merge(returnReasonOf(order, item), returned, Integer::sum);
                }
            }
            if (anyCancel) withCancel++;
            if (anyReturn) withReturn++;
        }

        List<ReasonsReport.Week> weekRows = new ArrayList<>();
        weeks.forEach((start, c) -> weekRows.add(new ReasonsReport.Week(start, c[0], c[1], c[2])));
        List<ReasonsReport.ProductRow> productRows = products.entrySet().stream()
                .filter(e -> e.getValue()[1] + e.getValue()[2] > 0)
                .map(e -> new ReasonsReport.ProductRow(e.getKey(), nameOf(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        Math.round(1000.0 * (e.getValue()[1] + e.getValue()[2]) / e.getValue()[0]) / 10.0))
                .sorted(Comparator.comparingInt((ReasonsReport.ProductRow r) -> r.unitsCancelled() + r.unitsReturned()).reversed()
                        .thenComparingInt(ReasonsReport.ProductRow::productId))
                .limit(MAX_PRODUCT_ROWS)
                .toList();
        return new ReasonsReport(from, to, orders.size(), withCancel, withReturn, unitsCancelled, unitsReturned,
                sorted(cancelReasons), sorted(returnReasons), weekRows, productRows);
    }

    private static List<ReasonsReport.Count> sorted(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(e -> new ReasonsReport.Count(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(ReasonsReport.Count::units).reversed().thenComparing(ReasonsReport.Count::reason))
                .toList();
    }

    private static String cancelReasonOf(Cart order) {
        String code = order.getCancelReason();
        return code == null || code.isBlank() ? NOT_GIVEN : code.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // A line's own reason (per-item return) wins over the order's; free text, so grouped case-insensitively.
    private static String returnReasonOf(Cart order, OrderItem item) {
        String reason = item.getReturnReason() != null && !item.getReturnReason().isBlank() ? item.getReturnReason() : order.getReturnReason();
        if (reason == null || reason.isBlank()) {
            return NOT_GIVEN;
        }
        String clean = reason.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return clean.length() > MAX_REASON_LENGTH ? clean.substring(0, MAX_REASON_LENGTH) : clean;
    }

    private final Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();

    private String nameOf(int productId) {
        String cached = names.get(productId);
        if (cached != null) {
            return cached;
        }
        try {
            Product p = productClient.getProductById(productId);
            if (p != null && p.getProductName() != null) {
                names.put(productId, p.getProductName());
                return p.getProductName();
            }
        } catch (FeignException e) {
            // fall through: a deleted product still gets a row
        }
        return "Product " + productId;
    }

    /** CSV of the whole report: one section per kind, header row first in each. */
    public String csv(ReasonsReport r) {
        StringBuilder out = new StringBuilder();
        out.append("section,key,ordersPlaced,unitsOrdered,unitsCancelled,unitsReturned,sentBackPercent\r\n");
        out.append(row("summary", r.from() + " to " + r.to(), r.ordersPlaced(), "", r.unitsCancelled(), r.unitsReturned(), ""));
        for (ReasonsReport.Count c : r.cancelReasons()) {
            out.append(row("cancel reason", c.reason(), "", "", c.units(), "", ""));
        }
        for (ReasonsReport.Count c : r.returnReasons()) {
            out.append(row("return reason", c.reason(), "", "", "", c.units(), ""));
        }
        for (ReasonsReport.Week w : r.weeks()) {
            out.append(row("week", w.weekStart(), w.ordersPlaced(), "", w.unitsCancelled(), w.unitsReturned(), ""));
        }
        for (ReasonsReport.ProductRow p : r.products()) {
            out.append(row("product", p.productId() + " " + p.productName(), "", p.unitsOrdered(), p.unitsCancelled(), p.unitsReturned(), p.sentBackPercent()));
        }
        return out.toString();
    }

    private static String row(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) line.append(',');
            line.append(cell(String.valueOf(cells[i])));
        }
        return line.append("\r\n").toString();
    }

    // Quote anything with a comma, quote or newline, and defuse spreadsheet formulas in free text.
    private static String cell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
