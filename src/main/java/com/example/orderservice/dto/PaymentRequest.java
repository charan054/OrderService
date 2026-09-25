package com.example.orderservice.dto;

import java.math.BigDecimal;

// Mirrors PhonepayService's PaymentRequest - what /phonepe/makepayment expects.
public record PaymentRequest(BigDecimal amount, String note, String idempotencyKey) {
}
