package com.example.orderservice.dto;

import java.util.List;

// Mirrors just the "content" field of the Page<Review> JSON that ProductService's GET /product/{id}/reviews
// returns - same pattern as ProductSearchResult, pagination metadata isn't needed here.
public record ProductReviewsResult(List<ProductReview> content) {
}
