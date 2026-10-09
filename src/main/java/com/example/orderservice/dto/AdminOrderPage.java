package com.example.orderservice.dto;

import java.util.List;

// One page of the admin orders table (GET /cart/orders/page). page is 0-based; matchingTotal is the summed
// totalPrice of EVERY matching order, not just this page.
public record AdminOrderPage(List<AdminOrderRow> rows, int page, int size, long totalElements, int totalPages,
                             double matchingTotal) {
}
