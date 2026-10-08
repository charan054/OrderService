package com.example.orderservice.dto;

import com.example.orderservice.entity.AdminRole;

import java.time.Instant;

// token goes back to the dashboard once, in plain text, to be sent as X-Admin-Token on later calls.
public record AdminLoginResponse(String token, String username, AdminRole role, Instant expiresAt) {
}
