package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// Printable receipt for one order - GET /cart/{orderId}/invoice?phno=X. Line unit prices are what was paid per unit
// for orders placed since OrderItem.unitPrice existed, and the CURRENT catalog price for older ones - so for older
// orders only the saved order's own figures (discountAmount, pointsRedeemed, totalPrice) are authoritative.
// refundedAmount and the per-line cancelled/returned quantities show any per-item changes since.
public record Invoice(long orderId, Instant placedAt, String customerName, long customerPhno,
                      List<Line> lines, String couponCode, double discountAmount, int pointsRedeemed,
                      double totalPrice, double refundedAmount, String paymentMethod, boolean paid, String status,
                      String shippingAddress, String deliveryNote, String deliverySlot) {
    public record Line(int productId, String productName, int quantity, double unitPrice, double lineTotal,
                       int cancelledQuantity, int returnedQuantity) {
    }
}
