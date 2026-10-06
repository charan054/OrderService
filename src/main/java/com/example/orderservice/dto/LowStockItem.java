package com.example.orderservice.dto;

// One product at or below its own low-stock threshold - part of GET /cart/lowstock (admin-only). waitlistCount is
// how many customers have tapped "Notify me" on it, i.e. demand that is currently going unmet.
public record LowStockItem(int productId, String productName, int productStock, int lowStockThreshold,
                           String level, long waitlistCount) {
}
