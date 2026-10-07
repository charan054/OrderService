package com.example.orderservice.dto;

import java.time.Instant;

// token goes back to the storefront once, in plain text, to be sent as X-Customer-Token on later calls.
public record CustomerLoginResponse(String token, long phno, Instant expiresAt) {
}
