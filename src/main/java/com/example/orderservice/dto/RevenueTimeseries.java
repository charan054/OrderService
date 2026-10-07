package com.example.orderservice.dto;

import java.time.LocalDate;
import java.util.List;

// GET /cart/analytics/timeseries - revenue (net of refunds) and order count per day or week. Every period in
// [from, to] is present, zeros included, so a chart's time axis has no gaps. undatedOrders counts orders that
// have no tracking history (placed before tracking existed) and so can't be put on the axis at all.
public record RevenueTimeseries(LocalDate from, LocalDate to, String bucket, String zone,
                                List<Point> points, double totalRevenue, long totalOrders, long undatedOrders) {
    // periodStart is the day itself, or the Monday that starts the week.
    public record Point(LocalDate periodStart, long orders, double revenue) {
    }
}
