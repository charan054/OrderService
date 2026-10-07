package com.example.orderservice.dto;

// POST /support/tickets body. category is one of SupportTicket.Category; productId and photoUrl are optional.
public record NewSupportTicket(long customerPhno, long orderId, String category, Integer productId, String message,
                               String photoUrl) {
}
