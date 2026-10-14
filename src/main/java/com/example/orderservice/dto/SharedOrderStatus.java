package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * What a shared order link shows: the order number, status, the dates each step was reached and, once shipped, the carrier
 * and tracking number. No address, items, prices or phone number.
 */
public record SharedOrderStatus(long orderId, String status, List<Step> timeline, String carrier, String trackingNumber,
                                String expectedDeliveryDate, Instant linkExpiresAt) {
    public record Step(String status, Instant timestamp) {
    }
}
