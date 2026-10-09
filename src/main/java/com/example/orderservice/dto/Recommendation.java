package com.example.orderservice.dto;

// One "Picked for you" suggestion (GET /cart/recommendations). reason is BOUGHT_WITH (shared an order with something
// this customer bought) or POPULAR (a best seller overall).
public record Recommendation(int productId, String productName, String productCategory, double productPrice,
                             String productImageUrl, String reason) {
}
