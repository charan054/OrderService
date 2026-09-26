package com.example.orderservice.dto;

// One wishlisted product whose current price has dropped below what it was when the customer wishlisted it -
// returned by GET /wishlist/pricedrops, computed fresh on every call (see OrderService.getPriceDropAlerts()).
public record WishlistPriceAlert(int productId, String productName, double priceWhenAdded, double currentPrice,
                                  double priceDrop) {
}
