package com.example.orderservice.dto;

// What an account deletion did. Orders are kept (invoices, tax records) with the name and phone number removed.
public record AccountDeletionResult(int ordersAnonymised, int addressesErased, int wishlistItems, int stockAlerts,
                                    int subscriptions, int supportTickets, int reviewsAnonymised,
                                    int loyaltyPointsForfeited, double storeCreditForfeited) {
}
