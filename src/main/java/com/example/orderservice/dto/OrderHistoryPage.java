package com.example.orderservice.dto;

import com.example.orderservice.entity.Cart;

import java.util.List;

// One page of a customer's own order history (GET /cart/history), newest first. page is 0-based.
public record OrderHistoryPage(List<Cart> orders, int page, int size, long totalElements, int totalPages) {
}
