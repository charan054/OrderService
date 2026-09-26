package com.example.orderservice.dto;

// One product that has actually been bought alongside the queried product, ranked by how often - returned by
// GET /cart/frequentlyboughttogether. A real upgrade over ProductService's same-category "related products",
// which has no purchase history to draw on; this one does, since OrderService owns the order data.
public record FrequentlyBoughtTogether(int productId, String productName, int timesBoughtTogether) {
}
