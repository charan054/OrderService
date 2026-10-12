package com.example.orderservice.dto;

import java.util.List;

/**
 * How each coupon performed (GET /coupons/report). Orders that still stand (placed, shipped, delivered) carry the
 * discount and revenue figures; cancelled and returned ones are only counted. discountGiven is the order's whole
 * discount, so it also includes any subscription discount on the same order. A code that was used but has since
 * been deleted still has a row (discountPercent null, known=false).
 */
public record CouponReport(List<Row> rows, double totalDiscountGiven, double totalNetRevenue) {
    public record Row(String code, boolean known, Double discountPercent, boolean active, boolean expired, boolean unlisted,
                      int orders, int standingOrders, int cancelledOrReturned, double discountGiven, double netRevenue,
                      Double averageOrderValue, int redemptionCount, Integer maxRedemptions) {
    }
}
