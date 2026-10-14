package com.example.orderservice.dto;

// Outcome of one birthday-coupon run: emails sent, and why others were skipped.
public record BirthdayCouponResult(int emailsSent, int customersWithoutEmail, int optedOut, int sendFailures) {
}
