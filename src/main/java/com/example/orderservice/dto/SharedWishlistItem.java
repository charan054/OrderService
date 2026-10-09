package com.example.orderservice.dto;

// What someone holding a wishlist share link sees per product - deliberately no phone number or price history.
public record SharedWishlistItem(int productId, String productName, String productCategory, double productPrice,
                                 String productImageUrl, boolean inStock) {
}
