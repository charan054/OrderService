package com.example.orderservice.dto;

import java.math.BigDecimal;
import java.time.Instant;

// Mirrors PhonepayService's UpiCollectRequestResponse field-for-field - what /phonepe/upi/collect and
// /phonepe/upi/collect/{merchantReference} return.
public record UpiCollectRequestResponse(long id, String merchantReference, long payerPhno, String payerUpiId,
                                         BigDecimal amount, String note, String status, Instant createdAt,
                                         Instant expiresAt, Instant resolvedAt, Long resultTransactionId) {
}
