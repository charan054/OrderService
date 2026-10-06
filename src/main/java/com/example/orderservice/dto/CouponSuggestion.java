package com.example.orderservice.dto;

import java.time.Instant;

// One coupon a given customer could use right now - part of GET /coupons/available?phno=X. usesLeft is null when
// the coupon has no per-customer limit.
public record CouponSuggestion(String code, double discountPercent, Instant expiryDate, Integer usesLeft) {
}
