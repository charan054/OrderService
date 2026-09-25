package com.example.orderservice.dto;

import java.math.BigDecimal;
import java.time.Instant;

// Mirrors PhonepayService's TransactionResponse field-for-field - what /phonepe/makepayment returns on success.
public record PaymentResponse(long transactionId, String mode, String direction, long payerPhno,
                               Long receiverPhno, BigDecimal amount, String status, Instant createdAt, String note) {
}
