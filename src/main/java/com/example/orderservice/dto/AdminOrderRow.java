package com.example.orderservice.dto;

import java.time.Instant;

// One order as shown in the admin orders table / CSV export (GET /cart/orders/search and /cart/orders/export,
// both X-Service-Key gated). items is a compact "productId x qty; ..." summary.
public record AdminOrderRow(long orderId, Instant placedAt, String customerName, long customerPhno, String status,
                            String paymentMethod, boolean paid, String items, String couponCode,
                            double discountAmount, int pointsRedeemed, double totalPrice, String deliveryNote) {
}
