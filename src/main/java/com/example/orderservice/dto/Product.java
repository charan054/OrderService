package com.example.orderservice.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@JsonPropertyOrder({
    "productId",
        "productName",
        "productCategory",
        "productPrice",
        "productStock"
})
@Data
public class Product {
    private int productId;
    private String productName;
    private String productCategory;
    private double productPrice;
    private int productStock;
}
