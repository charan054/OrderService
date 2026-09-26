package com.example.orderservice.dto;

import java.time.Instant;

// Mirrors PhonepayService's LoginResponse - what /phonepe/login returns. Only token is used here (turned into an
// Authorization header for makePayment/refund); the rest is ignored.
public record PhonepeLoginResponse(String token, Instant expiresAt, long phno, String name) {
}
