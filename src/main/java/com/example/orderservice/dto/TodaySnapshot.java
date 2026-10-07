package com.example.orderservice.dto;

import java.time.LocalDate;
import java.util.List;

// The admin's "how is today going" view (GET /cart/analytics/today). Counts are for the calendar day in `zone`:
// orders placed, their net revenue (orders that still stand, minus refunds), and how many orders were delivered or
// cancelled today. `attention` is what needs doing right now regardless of the day.
public record TodaySnapshot(LocalDate date, String zone, int ordersPlaced, double netRevenue, int delivered,
                            int cancelled, Attention attention) {
    // unshipped: PLACED orders waiting longer than staleHours to ship (ids, oldest first, at most 20);
    // cashDeliveredUnpaid: delivered cash-on-delivery orders whose payment hasn't been recorded as collected;
    // pendingPayments: UPI orders still waiting for the buyer; unansweredQuestions: product questions awaiting an answer.
    public record Attention(int staleHours, List<Long> unshipped, List<Long> cashDeliveredUnpaid,
                            int pendingPayments, int unansweredQuestions) {
    }
}
