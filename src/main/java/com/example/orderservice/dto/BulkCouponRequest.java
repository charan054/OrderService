package com.example.orderservice.dto;

import java.time.Instant;

// POST /coupons/bulk: how many single-use codes to mint and what each is worth. prefix is optional (default GIFT);
// expiryDate is optional (null = never expires).
public record BulkCouponRequest(String prefix, int count, double discountPercent, Instant expiryDate) {
}
