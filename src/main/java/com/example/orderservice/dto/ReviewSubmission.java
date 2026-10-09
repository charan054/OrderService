package com.example.orderservice.dto;

// Request body for posting a review through OrderService's proxy - mirrors the only fields ProductService's
// ReviewService.addReview() actually reads off the Review entity body (productId comes from the path there,
// not the body).
// photoUrl is optional: a photo this service issued (see ReviewPhotoService), or null.
public record ReviewSubmission(String reviewerName, long reviewerPhno, int rating, String comment, String photoUrl) {
    public ReviewSubmission(String reviewerName, long reviewerPhno, int rating, String comment) {
        this(reviewerName, reviewerPhno, rating, comment, null);
    }
}
