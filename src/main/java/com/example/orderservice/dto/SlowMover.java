package com.example.orderservice.dto;

import java.time.Instant;

// A product with stock on the shelf that has not sold lately. lastSoldAt is null (and daysSinceLastSale null) when
// no kept unit of it has ever been ordered. stockValue = stock x selling price: the money sitting on the shelf.
public record SlowMover(int productId, String productName, String productCategory, int productStock,
                        double stockValue, Instant lastSoldAt, Long daysSinceLastSale) {
}
