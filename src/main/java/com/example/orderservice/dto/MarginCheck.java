package com.example.orderservice.dto;

import java.util.List;

/**
 * What a coupon's percent off would do to the shop's margin (GET /coupons/margincheck). Only products with a cost price
 * set can be judged: productsWithCost says how many that is, so "nothing at a loss" is not mistaken for "checked
 * everything". atLoss lists the products that would sell below cost at that discount.
 */
public record MarginCheck(double discountPercent, int productsWithCost, List<AtLoss> atLoss) {
    public record AtLoss(int productId, String productName, double price, double costPrice, double priceAfterDiscount, double lossPerUnit) {
    }
}
