package com.example.orderservice.dto;

// What the storefront's logged-out "Track an order" box shows - status and totals only, no address or items.
public record GuestOrderSummary(long orderId, String status, double totalPrice, String paymentMethod, boolean paid) {
}
