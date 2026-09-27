package com.example.orderservice.dto;

import java.util.List;
import java.util.Map;

// Admin-only sales summary returned by GET /cart/analytics (X-Service-Key gated, same trust level as GET
// /cart/all which this is built from). Revenue figures use totalPrice as actually charged (post-coupon/points
// discount), excluding CANCELLED orders (fully refunded, never a kept sale) but including RETURNED ones (a real
// sale even if later reversed - no separate "returns" bucket exists yet to net them back out).
public record SalesAnalytics(long totalOrders,
                              double totalRevenue,
                              Map<String, Long> ordersByStatus,
                              Map<String, Double> revenueByPaymentMethod,
                              List<TopSellingProduct> topProducts) {
}
