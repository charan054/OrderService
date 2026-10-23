package com.example.orderservice.dto;

// Body of POST /subscriptions. startNow (default true) places the first order right away; false waits one interval.
// mandateId (optional): pay each delivery from the customer's PhonePe account through that mandate instead of cash on delivery.
public record SubscriptionRequest(long phno, String customerName, int productId, int quantity, int intervalDays,
                                  Long shippingAddressId, Boolean startNow, Long mandateId) {
    public SubscriptionRequest(long phno, String customerName, int productId, int quantity, int intervalDays,
                               Long shippingAddressId, Boolean startNow) {
        this(phno, customerName, productId, quantity, intervalDays, shippingAddressId, startNow, null);
    }
}
