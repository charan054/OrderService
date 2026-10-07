package com.example.orderservice.dto;

import java.util.List;

// Just the "content" of the Page<Review> JSON ProductService's GET /product/reviews/flagged returns - the rest of
// a Page's fields are ignored, same approach as ProductReviewsResult.
public record ModerationReviewsResult(List<ModerationReview> content) {
}
