package com.example.orderservice.dto;

import java.util.List;

// Admin rollup of who buys, from orders that still stand (PLACED / SHIPPED / DELIVERED - not cancelled, returned or
// awaiting payment). newCustomers placed exactly one such order, repeatCustomers two or more; repeatRate is the
// repeat share of all customers as a percentage (null with no customers). averageOrderValue is net of refunds.
public record CustomerInsights(int totalCustomers, int newCustomers, int repeatCustomers, Double repeatRate,
                               int totalOrders, double netRevenue, Double averageOrderValue,
                               Double averageOrdersPerCustomer, List<TopCustomer> topCustomers) {
    public record TopCustomer(long phno, String name, int orders, double netSpend) {
    }
}
