package com.example.orderservice.dto;

// Whether this phone number may pay cash on delivery right now, and why not. maxAmount is the largest order total
// cash is accepted for (null = no cap). override is ALLOW / BLOCK when an admin has decided, null when the
// automatic rules apply; overrideNote is the admin's reason. failedCashOrders counts cash orders that ended
// CANCELLED or RETURNED inside the look-back window; hasDeliveredOrder is whether any order has ever been delivered.
public record CodEligibility(boolean available, String reason, Double maxAmount, String override, String overrideNote,
                             int failedCashOrders, boolean hasDeliveredOrder) {
}
