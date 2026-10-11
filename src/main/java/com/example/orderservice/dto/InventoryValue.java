package com.example.orderservice.dto;

import java.util.List;

// What the stock on the shelves is worth at the current selling price (GST-inclusive, as listed), overall and per
// category. Products with no stock are counted in products but add nothing to units or value.
public record InventoryValue(int products, long units, double totalValue, List<CategoryValue> categories) {
    public record CategoryValue(String category, int products, long units, double value) {
    }
}
