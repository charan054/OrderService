package com.example.orderservice.dto;

import com.example.orderservice.entity.LoyaltyTier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Everything the admin dashboard shows about one customer in a single call (GET /customer/admin/lookup).
// email/emailVerifiedAt are null for a phone that has never signed in to the storefront. netSpend counts only
// orders that still stand (not cancelled/returned/awaiting payment) minus any per-item refunds.
public record AdminCustomerLookup(long phno, String email, Instant emailVerifiedAt, int totalOrders,
                                  Map<String, Integer> ordersByStatus, double netSpend,
                                  int wishlistCount, long reviewCount, LoyaltyTier loyaltyTier,
                                  int loyaltyPointsBalance, long lifetimePointsEarned,
                                  List<RecentOrder> recentOrders, List<AddressLine> addresses) {
    public record RecentOrder(long orderId, String status, String paymentMethod, boolean paid, double totalPrice,
                              double refundedAmount) {
    }

    public record AddressLine(long id, String label, String address, boolean isDefault) {
    }
}
