package com.example.orderservice.dto;

// Outcome of one low-stock alert run: sent=false with a reason when nothing was emailed.
public record LowStockAlertResult(boolean sent, int products, String reason) {
}
