package com.example.orderservice.dto;

public record ResetPinRequest(long phno, String otp, String newPin) {
}
