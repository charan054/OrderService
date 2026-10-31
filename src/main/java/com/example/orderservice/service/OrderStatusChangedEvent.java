package com.example.orderservice.service;

import com.example.orderservice.entity.OrderStatus;

import java.time.Instant;

// Published by OrderService each time it records a new status for an order, so outbound webhooks can react
// without OrderService depending on them.
public record OrderStatusChangedEvent(long orderId, OrderStatus status, Instant at) {
}
