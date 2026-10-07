package com.example.orderservice.dto;

import java.time.Instant;

// An answered question as shown to any storefront visitor - deliberately without who asked it.
public record PublicQuestion(long id, String question, String answer, Instant answeredAt) {
}
