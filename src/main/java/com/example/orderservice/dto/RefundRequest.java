package com.example.orderservice.dto;

// Mirrors PhonepayService's RefundRequest - what /phonepe/transactions/{id}/refund expects.
// amount null = everything still refundable on the payment (a full refund the first time).
public record RefundRequest(String idempotencyKey, java.math.BigDecimal amount) {
}
