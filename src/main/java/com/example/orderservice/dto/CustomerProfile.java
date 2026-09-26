package com.example.orderservice.dto;

import com.example.orderservice.entity.LoyaltyTier;

// A one-lookup rollup of who a customer is across both services - orders/wishlist (owned here) plus loyalty
// (also owned here) plus a review count (owned by ProductService, fetched via Feign). Read-only and computed
// fresh on every call, same "no caching, no push" reasoning as getPriceDropAlerts()/getFrequentlyBoughtTogether().
public record CustomerProfile(long customerPhno, int totalOrders, int wishlistCount, long reviewCount,
                               LoyaltyTier loyaltyTier, int loyaltyPointsBalance, long lifetimePointsEarned) {
}
