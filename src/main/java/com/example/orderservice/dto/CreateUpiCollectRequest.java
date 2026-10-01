package com.example.orderservice.dto;

import java.math.BigDecimal;

// Mirrors PhonepayService's CreateUpiCollectRequest - what POST /phonepe/upi/collect expects.
public record CreateUpiCollectRequest(String merchantReference, String upiId, BigDecimal amount, String note) {
}
