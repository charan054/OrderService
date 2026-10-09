package com.example.orderservice.dto;

// One row of GET /cart/health: status is UP or DOWN, responseMillis how long the probe took, detail a short note.
public record ServiceHealth(String name, String status, long responseMillis, String detail) {
}
