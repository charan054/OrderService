package com.example.orderservice.dto;

import java.time.LocalDateTime;

// Mirrors the fields of ProductService's own Review that the storefront actually needs - reviewId/reviewerName/
// rating/comment/createdAt. Moderation-only fields (flagged/flagReason/hidden) are omitted: a hidden review
// never reaches this list anyway (ProductService's listReviews() already excludes them), and flag state is an
// admin concern, not something a customer browsing reviews needs to see.
// productId is the option (variant) the review was written for: ProductService lists the reviews of a whole variant
// group under any of its options, so the review can belong to a sibling of the product that was asked for.
public record ProductReview(long reviewId, String reviewerName, long reviewerPhno, int rating, String comment,
                             LocalDateTime createdAt, Long productId) {
    public ProductReview(long reviewId, String reviewerName, long reviewerPhno, int rating, String comment,
                         LocalDateTime createdAt) {
        this(reviewId, reviewerName, reviewerPhno, rating, comment, createdAt, null);
    }
}
