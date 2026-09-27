package com.example.orderservice.dto;

// One product ranked by units actually sold - part of GET /cart/analytics's top-products list.
public record TopSellingProduct(int productId, String productName, int unitsSold, double revenue) {
}
