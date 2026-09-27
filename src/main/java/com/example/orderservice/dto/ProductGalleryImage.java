package com.example.orderservice.dto;

// Mirrors ProductService's ProductImage - one additional gallery photo, shown in the storefront's
// product-details modal alongside the cover Product.productImageUrl.
public record ProductGalleryImage(long id, int productId, String imageUrl) {
}
