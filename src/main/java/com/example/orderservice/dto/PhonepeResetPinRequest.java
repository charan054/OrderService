package com.example.orderservice.dto;

// Mirrors PhonepayService's ResetPinRequest - what /phonepe/forgotpin/reset expects.
public record PhonepeResetPinRequest(long phno, String otp, String newPin) {
}
