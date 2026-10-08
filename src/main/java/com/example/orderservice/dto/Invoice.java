package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

// Printable receipt for one order - GET /cart/{orderId}/invoice?phno=X. Line unit prices are what was paid per unit
// for orders placed since OrderItem.unitPrice existed, and the CURRENT catalog price for older ones - so for older
// orders only the saved order's own figures (discountAmount, pointsRedeemed, totalPrice) are authoritative.
// refundedAmount and the per-line cancelled/returned quantities show any per-item changes since.
public record Invoice(long orderId, Instant placedAt, String customerName, long customerPhno,
                      List<Line> lines, String couponCode, double discountAmount, int pointsRedeemed,
                      double storeCreditUsed, double totalPrice, double refundedAmount, String paymentMethod, boolean paid, String status,
                      String shippingAddress, String deliveryNote, String deliverySlot,
                      String invoiceNumber, Instant invoiceDate, Tax tax) {
    // GST breakdown of what was actually supplied (see GstCalculator). Prices include GST, so this splits the tax out of
    // them rather than adding anything. Seller GSTIN/state come from gst.store-gstin / gst.store-state.
    public record Tax(String sellerName, String sellerGstin, String sellerState, String placeOfSupply, boolean interState,
                      List<TaxLine> lines, double taxableValue, double cgst, double sgst, double igst, double totalTax) {
    }

    public record TaxLine(int productId, String productName, String hsnCode, double gstRate, int quantity,
                          double taxableValue, double cgst, double sgst, double igst, double total) {
    }

    public record Line(int productId, String productName, int quantity, double unitPrice, double lineTotal,
                       int cancelledQuantity, int returnedQuantity) {
    }
}
