package com.example.orderservice.dto;

import java.time.LocalDateTime;

// Mirrors the fields of ProductService's own Review that the storefront actually needs - reviewId/reviewerName/
// rating/comment/createdAt. Moderation-only fields (flagged/flagReason/hidden) are omitted: a hidden review
// never reaches this list anyway (ProductService's listReviews() already excludes them), and flag state is an
// admin concern, not something a customer browsing reviews needs to see.
public record ProductReview(long reviewId, String reviewerName, long reviewerPhno, int rating, String comment,
                             LocalDateTime createdAt) {
}
