package com.example.orderservice.dto;

import java.util.List;

// Outcome of POST /cart/bulk/ship or /bulk/deliver: one entry per distinct order id, in request order. A failure
// on one order (not found, wrong status) never stops the others.
public record BulkTransitionResult(String action, int succeeded, int failed, List<Item> results) {
    public record Item(long orderId, boolean success, String message) {
    }
}
