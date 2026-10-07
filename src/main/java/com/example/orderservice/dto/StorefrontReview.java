package com.example.orderservice.dto;

import java.time.LocalDateTime;

// A review as the storefront shows it: ProductReview minus the reviewer's phone number (not something to put in a
// public listing) plus verifiedPurchase - true when that reviewer has a kept (non-cancelled) order containing
// this product in OrderService.
public record StorefrontReview(long reviewId, String reviewerName, int rating, String comment,
                               LocalDateTime createdAt, boolean verifiedPurchase) {
}
