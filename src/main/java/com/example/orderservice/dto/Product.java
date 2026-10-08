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
}
