package com.example.orderservice.dto;

// Mirrors ProductService's own RatingSummary, returned by its public GET /product/{id}/rating-summary.
public record ProductRatingSummary(long productId, double averageRating, long reviewCount) {
}
