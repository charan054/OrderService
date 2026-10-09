package com.example.orderservice.dto;

import java.time.LocalDateTime;

// A review as the storefront shows it: ProductReview minus the reviewer's phone number (not something to put in a
// public listing) plus verifiedPurchase - true when that reviewer has a kept (non-cancelled) order containing
// this product in OrderService.
// productId is the option the review was written for (see ProductReview), so a group card can say which one.
// photoUrl is the optional photo the reviewer attached (null when none).
public record StorefrontReview(long reviewId, String reviewerName, int rating, String comment,
                               LocalDateTime createdAt, boolean verifiedPurchase, Long productId, String photoUrl) {
    public StorefrontReview(long reviewId, String reviewerName, int rating, String comment,
                            LocalDateTime createdAt, boolean verifiedPurchase, Long productId) {
        this(reviewId, reviewerName, rating, comment, createdAt, verifiedPurchase, productId, null);
    }

    public StorefrontReview(long reviewId, String reviewerName, int rating, String comment,
                            LocalDateTime createdAt, boolean verifiedPurchase) {
        this(reviewId, reviewerName, rating, comment, createdAt, verifiedPurchase, null, null);
    }
}
