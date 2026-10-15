package com.example.orderservice.dto;

// The only part of ProductService's answer to a "helpful" vote that OrderService needs. Its public review view has no
// reviewerPhno, so it cannot be read as a ProductReview.
public record ReviewHelpfulCount(int helpfulCount) {
}
