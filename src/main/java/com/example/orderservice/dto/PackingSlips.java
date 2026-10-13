package com.example.orderservice.dto;

import java.util.List;

// What the warehouse prints for a batch of orders: one slip per order and one pick list for the whole batch.
public record PackingSlips(List<Slip> slips, List<PickLine> pickList) {
    public record Slip(long orderId, String customerName, long customerPhno, String address, String deliverySlot,
                       String deliveryNote, boolean giftWrap, String giftMessage, String paymentMethod, double amountToCollect,
                       List<Line> lines) {
    }

    public record Line(int productId, String name, int quantity) {
    }

    // Units of one product across every order in the batch - what to pull off the shelves in one trip.
    public record PickLine(int productId, String name, int quantity, int orders) {
    }
}
