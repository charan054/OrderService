package com.example.orderservice.dto;

// What one pending-payment sweep did: how many PENDING_PAYMENT orders it looked at, and how many of those it moved
// on (paid and placed, declined, or expired and cancelled with their stock put back).
public record PendingPaymentSweepResult(int checked, int resolved) {
}
