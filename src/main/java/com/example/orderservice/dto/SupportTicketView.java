package com.example.orderservice.dto;

import com.example.orderservice.entity.SupportMessage;
import com.example.orderservice.entity.SupportTicket;

import java.util.List;

// One support ticket with its whole conversation, oldest message first, plus where the order it is about stands
// (null if that order no longer exists) - enough for staff to decide on a refund/return without another lookup.
public record SupportTicketView(SupportTicket ticket, List<SupportMessage> messages, OrderInfo order) {
    public record OrderInfo(long orderId, String status, String paymentMethod, boolean paid, double totalPrice,
                            double refundedAmount) {
    }
}
