package com.example.orderservice.dto;

// Mirrors PhonepayService's ForgotPinRequest - what /phonepe/forgotpin/request expects.
public record PhonepeForgotPinRequest(long phno) {
}
