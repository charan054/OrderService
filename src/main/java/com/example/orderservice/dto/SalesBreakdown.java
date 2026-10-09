package com.example.orderservice.dto;

import java.time.LocalDate;
import java.util.List;

// GET /cart/analytics/breakdown - units sold and revenue per product or per category between from and to. Revenue is
// each order's net-of-refunds amount shared out over its lines in proportion to what they cost, so coupons and
// refunds are already in the figures and the rows add up to the totals (before the row limit is applied).
public record SalesBreakdown(LocalDate from, LocalDate to, String zone, String groupBy, List<Row> rows,
                             long totalUnits, long totalOrders, double totalRevenue, boolean truncated) {
    // key is the product id or the category name; category is only set when grouping by product.
    public record Row(String key, String label, String category, long units, long orders, double revenue, double sharePercent) {
    }
}
