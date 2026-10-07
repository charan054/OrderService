package com.example.orderservice.dto;

// POST /cart/{orderId}/invoice/email - where the invoice went, with the address partly masked (a***@example.com).
public record InvoiceEmailResult(String sentTo) {
}
