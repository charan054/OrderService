package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// Printable receipt for one order - GET /cart/{orderId}/invoice?phno=X. Line unit prices are the CURRENT catalog
// prices (OrderItem doesn't snapshot what was paid per unit), so only the figures from the saved order itself
// (discountAmount, pointsRedeemed, totalPrice) are authoritative for what the customer was actually charged.
public record Invoice(long orderId, Instant placedAt, String customerName, long customerPhno,
                      List<Line> lines, String couponCode, double discountAmount, int pointsRedeemed,
                      double totalPrice, String paymentMethod, boolean paid, String status,
                      String shippingAddress, String deliveryNote) {
    public record Line(int productId, String productName, int quantity, double unitPrice, double lineTotal) {
    }
}
