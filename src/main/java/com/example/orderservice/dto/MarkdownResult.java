package com.example.orderservice.dto;

// A price markdown that was applied: the catalog price before and after (ProductService also records it in the
// product's price history, which is what triggers wishlist price-drop alerts).
public record MarkdownResult(int productId, String productName, double oldPrice, double newPrice, int percent) {
}
