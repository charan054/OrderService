package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// The codes minted by one POST /coupons/bulk. This response is the only place they are listed for handing out
// (they are also in GET /coupons/all, but never in the customer-facing suggestions).
public record BulkCouponResult(String prefix, double discountPercent, Instant expiryDate, List<String> codes) {
}
