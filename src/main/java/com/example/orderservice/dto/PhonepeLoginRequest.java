package com.example.orderservice.dto;

// Mirrors PhonepayService's LoginRequest - what /phonepe/login expects.
public record PhonepeLoginRequest(long phno, String pin) {
}
