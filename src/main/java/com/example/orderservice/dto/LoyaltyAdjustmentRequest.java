package com.example.orderservice.dto;

// Body for POST /loyalty/adjust - a manual admin correction to a customer's points balance.
public record LoyaltyAdjustmentRequest(long customerPhno, int points, String reason) {
}
