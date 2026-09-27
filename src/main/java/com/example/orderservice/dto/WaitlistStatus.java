package com.example.orderservice.dto;

// One product a customer is waiting on - returned by GET /waitlist/byphno, with live stock re-fetched fresh on
// every call (see OrderService.getWaitlist() for why this isn't pushed/persisted anywhere).
public record WaitlistStatus(int productId, String productName, int currentStock, boolean inStock) {
}
