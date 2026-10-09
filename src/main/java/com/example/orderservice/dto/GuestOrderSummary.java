package com.example.orderservice.dto;

// What the storefront's logged-out "Track an order" box shows - status, totals and, once shipped, the carrier and
// tracking number (null before that); no address or items.
public record GuestOrderSummary(long orderId, String status, double totalPrice, String paymentMethod, boolean paid,
                                String carrier, String trackingNumber) {
}
