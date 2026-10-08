package com.example.orderservice.dto;

// Body of POST /subscriptions. startNow (default true) places the first order right away; false waits one interval.
public record SubscriptionRequest(long phno, String customerName, int productId, int quantity, int intervalDays,
                                  Long shippingAddressId, Boolean startNow) {
}
