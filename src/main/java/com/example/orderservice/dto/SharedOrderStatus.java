package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * What a shared order link shows: the order number, status, the dates each step was reached and, once shipped, the carrier
 * and tracking number (plus the carrier's own tracking page when we know it, and when it went out for delivery). No
 * address, items, prices or phone number.
 */
public record SharedOrderStatus(long orderId, String status, List<Step> timeline, String carrier, String trackingNumber,
                                String expectedDeliveryDate, Instant linkExpiresAt, String trackingUrl, Instant outForDeliveryAt) {
    public record Step(String status, Instant timestamp) {
    }
}
