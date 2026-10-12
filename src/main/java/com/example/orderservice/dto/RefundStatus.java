package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * Where the money of an order's cancellations and returns went (GET /cart/{orderId}/refunds). refundedTotal is
 * split into what went back through PhonePe, what went into store credit, and (cash orders only) what simply stopped
 * being due. storeCreditReturned is separate: store credit the buyer had USED on the order, handed back to the wallet.
 */
public record RefundStatus(long orderId, String orderStatus, String paymentMethod, double orderTotal, double refundedTotal,
                           double toPhonePe, double toStoreCredit, double noLongerDue, double storeCreditReturned,
                           Instant lastChangeAt, List<Entry> entries, String summary) {
    /** One credit into the wallet that belongs to this order. */
    public record Entry(Instant at, double amount, String note) {
    }
}
