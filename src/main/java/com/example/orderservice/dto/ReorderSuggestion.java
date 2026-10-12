package com.example.orderservice.dto;

// Buy-more advice for one product: how fast it has been selling, how long the stock lasts at that pace, and how many
// units would cover the requested number of days. daysOfStock is null when nothing sold in the window (it is not
// running out). Sorted soonest-to-run-out first.
public record ReorderSuggestion(int productId, String productName, String productCategory, int productStock,
                                long unitsSold, double dailyRate, Double daysOfStock, int suggestedQuantity) {
}
