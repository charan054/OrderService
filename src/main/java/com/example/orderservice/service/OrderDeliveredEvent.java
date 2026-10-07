package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;

// Published by OrderService.deliver() once an order is saved as DELIVERED, so other features (referral bonuses)
// can react without OrderService depending on them.
public record OrderDeliveredEvent(Cart order) {
}
