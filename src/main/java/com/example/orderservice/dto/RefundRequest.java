package com.example.orderservice.dto;

// Mirrors PhonepayService's RefundRequest - what /phonepe/transactions/{id}/refund expects.
public record RefundRequest(String idempotencyKey) {
}
