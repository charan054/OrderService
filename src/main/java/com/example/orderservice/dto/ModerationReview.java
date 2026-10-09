package com.example.orderservice.dto;

import java.time.LocalDateTime;

// Mirrors ProductService's full Review entity as the admin moderation queue sees it - unlike ProductReview (the
// public listing) this includes the reviewer's phone number and the flag/hidden state. Admin-only (X-Service-Key).
// photoUrl is the photo attached to the review, if any, so a moderator can look at it before deciding.
public record ModerationReview(long reviewId, long productId, String reviewerName, long reviewerPhno, int rating,
                               String comment, LocalDateTime createdAt, boolean flagged, String flagReason,
                               boolean hidden, String photoUrl) {
    public ModerationReview(long reviewId, long productId, String reviewerName, long reviewerPhno, int rating,
                            String comment, LocalDateTime createdAt, boolean flagged, String flagReason, boolean hidden) {
        this(reviewId, productId, reviewerName, reviewerPhno, rating, comment, createdAt, flagged, flagReason, hidden, null);
    }
}
