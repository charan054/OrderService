package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.SalesBreakdown;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The admin "Sales breakdown": what sold, by product or by category, over a date range. Same conventions as the
 * revenue-over-time chart: an order belongs to the day it was placed (its first tracking event, in the time zone of
 * the person asking), cancelled, returned and still-unpaid orders are left out, and revenue is net of refunds.
 * <p>
 * An order's net amount is shared over its lines in proportion to unit price x units still kept, so a coupon or a
 * partial refund lowers every line fairly; orders from before unit prices were recorded are shared by units instead.
 * Names and categories come from the live catalog, so a renamed product shows its current name; if the catalog cannot
 * be reached the rows fall back to "Product #id" / "(unknown)" instead of failing.
 */
@Service
public class SalesBreakdownService {
    static final int MAX_DAYS = 366;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    static final String UNKNOWN_CATEGORY = "(unknown)";

    private final CartRepository orders;
    private final TrackingEventRepository tracking;
    private final ProductClient productClient;

    public SalesBreakdownService(CartRepository orders, TrackingEventRepository tracking, ProductClient productClient) {
        this.orders = orders;
        this.tracking = tracking;
        this.productClient = productClient;
    }

    private static final class Acc {
        String label;
        String category;
        long units;
        double revenue;
        final Set<Long> orderIds = new HashSet<>();
    }

    public SalesBreakdown breakdown(LocalDate from, LocalDate to, String zone, String groupBy, String sort, String dir, Integer limit) {
        int max = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return compute(from, to, zone, groupBy, sort, dir, max);
    }

    // Every row, as CSV (RFC 4180 quoting; free text starting with = + - @ is neutralised, same as the orders export).
    public String exportCsv(LocalDate from, LocalDate to, String zone, String groupBy, String sort, String dir) {
        SalesBreakdown result = compute(from, to, zone, groupBy, sort, dir, Integer.MAX_VALUE);
        boolean byProduct = result.groupBy().equals("product");
        StringBuilder csv = new StringBuilder(byProduct
                ? "productId,product,category,units,orders,revenue,sharePercent\r\n"
                : "category,units,orders,revenue,sharePercent\r\n");
        for (SalesBreakdown.Row r : result.rows()) {
            if (byProduct) {
                csv.append(OrderService.csvCell(r.key())).append(',').append(OrderService.csvCell(r.label())).append(',')
                        .append(OrderService.csvCell(r.category())).append(',');
            } else {
                csv.append(OrderService.csvCell(r.label())).append(',');
            }
            csv.append(r.units()).append(',').append(r.orders()).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", r.revenue())).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", r.sharePercent())).append("\r\n");
        }
        return csv.toString();
    }

    private SalesBreakdown compute(LocalDate from, LocalDate to, String zone, String groupBy, String sort, String dir, int max) {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneOffset.UTC : ZoneId.of(zone.trim());
        } catch (java.time.DateTimeException e) {
            throw new ProductException("Unknown time zone: " + zone);
        }
        String group = groupBy == null || groupBy.isBlank() ? "product" : groupBy.trim().toLowerCase(Locale.ROOT);
        if (!group.equals("product") && !group.equals("category")) {
            throw new ProductException("groupBy must be product or category");
        }
        String sortKey = sort == null || sort.isBlank() ? "revenue" : sort.trim().toLowerCase(Locale.ROOT);
        if (!List.of("revenue", "units", "orders", "label").contains(sortKey)) {
            throw new ProductException("sort must be revenue, units, orders or label");
        }
        boolean ascending = sortKey.equals("label");
        if (dir != null && !dir.isBlank()) {
            if (dir.equalsIgnoreCase("asc")) ascending = true;
            else if (dir.equalsIgnoreCase("desc")) ascending = false;
            else throw new ProductException("dir must be asc or desc");
        }
        LocalDate end = to != null ? to : LocalDate.now(zoneId);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new ProductException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new ProductException("The range can be at most " + MAX_DAYS + " days");
        }

        Map<Integer, Product> catalog = new HashMap<>();
        try {
            for (Product p : productClient.findAll()) {
                catalog.put(p.getProductId(), p);
            }
        } catch (RuntimeException e) {
            // Catalog unreachable: rows fall back to ids and "(unknown)".
        }
        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent event : tracking.findAll()) {
            placedAt.merge(event.getOrderId(), event.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
        }

        Map<String, Acc> rows = new LinkedHashMap<>();
        Set<Long> countedOrders = new HashSet<>();
        for (Cart order : orders.findAll()) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.PENDING_PAYMENT
                    || order.getStatus() == OrderStatus.RETURNED || order.getOrderItems() == null) {
                continue;
            }
            Instant at = placedAt.get(order.getOrderId());
            if (at == null) {
                continue;
            }
            LocalDate day = at.atZone(zoneId).toLocalDate();
            if (day.isBefore(start) || day.isAfter(end)) {
                continue;
            }
            List<OrderItem> kept = order.getOrderItems().stream().filter(i -> i.getOutstandingQuantity() > 0).toList();
            if (kept.isEmpty()) {
                continue;
            }
            boolean allPriced = kept.stream().allMatch(i -> i.getUnitPrice() != null);
            double totalWeight = kept.stream().mapToDouble(i -> weight(i, allPriced)).sum();
            double net = OrderService.netPaid(order);
            countedOrders.add(order.getOrderId());
            for (OrderItem item : kept) {
                Product product = catalog.get(item.getProductId());
                String category = product == null || product.getProductCategory() == null || product.getProductCategory().isBlank()
                        ? UNKNOWN_CATEGORY : product.getProductCategory();
                String key = group.equals("product") ? String.valueOf(item.getProductId()) : category;
                Acc acc = rows.computeIfAbsent(key, k -> new Acc());
                acc.label = group.equals("product")
                        ? (product == null ? "Product #" + item.getProductId() : product.getProductName())
                        : category;
                acc.category = group.equals("product") ? category : null;
                acc.units += item.getOutstandingQuantity();
                acc.revenue += totalWeight > 0 ? net * weight(item, allPriced) / totalWeight : 0;
                acc.orderIds.add(order.getOrderId());
            }
        }

        double totalRevenue = rows.values().stream().mapToDouble(a -> a.revenue).sum();
        long totalUnits = rows.values().stream().mapToLong(a -> a.units).sum();
        List<SalesBreakdown.Row> all = new ArrayList<>();
        for (Map.Entry<String, Acc> e : rows.entrySet()) {
            Acc a = e.getValue();
            double share = totalRevenue > 0 ? a.revenue / totalRevenue * 100 : 0;
            all.add(new SalesBreakdown.Row(e.getKey(), a.label, a.category, a.units, a.orderIds.size(),
                    round(a.revenue), Math.round(share * 10) / 10.0));
        }
        Comparator<SalesBreakdown.Row> byLabel = Comparator.comparing(r -> String.valueOf(r.label()).toLowerCase(Locale.ROOT));
        Comparator<SalesBreakdown.Row> comparator = switch (sortKey) {
            case "units" -> Comparator.comparingLong(SalesBreakdown.Row::units);
            case "orders" -> Comparator.comparingLong(SalesBreakdown.Row::orders);
            case "label" -> byLabel;
            default -> Comparator.comparingDouble(SalesBreakdown.Row::revenue);
        };
        if (!ascending) comparator = comparator.reversed();
        all.sort(comparator.thenComparing(byLabel).thenComparing(SalesBreakdown.Row::key));
        boolean truncated = all.size() > max;
        List<SalesBreakdown.Row> shown = truncated ? new ArrayList<>(all.subList(0, max)) : all;
        return new SalesBreakdown(start, end, zoneId.getId(), group, shown, totalUnits, countedOrders.size(),
                round(totalRevenue), truncated);
    }

    private static double weight(OrderItem item, boolean allPriced) {
        return allPriced ? item.getUnitPrice() * item.getOutstandingQuantity() : item.getOutstandingQuantity();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
