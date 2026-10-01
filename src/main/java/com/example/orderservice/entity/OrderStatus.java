package com.example.orderservice.entity;

public enum OrderStatus {
    // A UPI-collect checkout that's been created (stock reserved) but not yet paid - the buyer still needs to
    // approve the collect request in PhonepayService. Resolves to PLACED (approved in time) or CANCELLED
    // (declined, or the payment window expired) - see OrderService.checkPendingPayment().
    PENDING_PAYMENT,
    PLACED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURNED
}
