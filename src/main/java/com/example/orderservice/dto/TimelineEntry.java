package com.example.orderservice.dto;

import java.time.Instant;

/** One step in an order's story for the customer (GET /cart/{orderId}/timeline): a status change, a return step or a refund. */
public record TimelineEntry(Instant at, String kind, String title) {
}
