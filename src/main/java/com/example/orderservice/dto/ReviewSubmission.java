package com.example.orderservice.dto;

// Request body for posting a review through OrderService's proxy - mirrors the only fields ProductService's
// ReviewService.addReview() actually reads off the Review entity body (productId comes from the path there,
// not the body).
public record ReviewSubmission(String reviewerName, long reviewerPhno, int rating, String comment) {
}
