package com.example.orderservice.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@JsonPropertyOrder({
    "productId",
        "productName",
        "productCategory",
        "productPrice",
        "productStock",
        "productImageUrl",
        "lowStockThreshold"
})
@Data
public class Product {
    private int productId;
    private String productName;
    private String productCategory;
    private double productPrice;
    private int productStock;
    private String productImageUrl;
    // Mirrors ProductService's per-product threshold (default 5 there too); used by OrderService.getLowStockReport().
    private int lowStockThreshold = 5;
    // GST rate in percent (prices include it) and HSN code, both optional - see ProductService's Product.
    private Double gstRate;
    private String hsnCode;
    // Products that are options of one thing (sizes, packs) share a variantGroup and each has its own variantLabel -
    // see ProductService's Product. Every option is a product in its own right (own id, price, stock), so nothing in
    // the cart or checkout depends on these; the storefront only uses them to show the options as one card.
    private String variantGroup;
    private String variantLabel;
}
