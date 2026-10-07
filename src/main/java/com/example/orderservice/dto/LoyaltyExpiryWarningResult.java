package com.example.orderservice.dto;

// What one expiry-warning run did: customers warned by email, customers whose points are about to expire but have no
// verified email (nothing recorded, so they are warned once they have one), and sends that failed (retried next run).
public record LoyaltyExpiryWarningResult(int emailsSent, int customersWithoutEmail, int sendFailures) {
}
