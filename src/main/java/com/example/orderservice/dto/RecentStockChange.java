package com.example.orderservice.dto;

import java.time.Instant;

// A hand-made stock change (CORRECTION or RESTOCK) from ProductService's stock ledger, as shown in the admin daily
// digest. Mirrors ProductService's RecentStockChange.
public record RecentStockChange(Integer productId, String productName, String type, int delta, int stockAfter,
                                String reason, String reference, String actor, Instant createdAt) {
}
