package com.example.orderservice.dto;

import java.util.List;

// A bundle as the storefront shows it, priced from the live catalog. savings is what one set saves against buying the
// parts separately right now (0 when a sale already makes the parts cheaper than the bundle).
public record BundleView(long id, String name, double bundlePrice, double regularTotal, double savings, boolean available,
                         List<Line> items) {
    public record Line(int productId, String productName, int quantity, double unitPrice, int stock) {
    }
}
