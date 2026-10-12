package com.example.orderservice.dto;

// Mirrors ProductService's ProductCost (GET /product/costs, service key only): a product's selling price and what a unit costs the shop.
public record ProductCost(int productId, String productName, double productPrice, double costPrice) {
}
