package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * Cash-on-delivery money: what staff have collected (per day, from the day they marked it paid), what is still owed on
 * orders that are placed, shipped or delivered, and the delivered orders whose cash is overdue. Amounts are net of refunds.
 */
public record CodCollectionReport(Instant generatedAt, String zone, int overdueAfterDays, double collectedToday,
                                  List<Day> collectedByDay, double outstandingTotal, int outstandingOrders,
                                  double overdueTotal, List<Overdue> overdue) {
    public record Day(String date, double collected, int orders) {
    }

    public record Overdue(long orderId, long customerPhno, double amountDue, Instant deliveredAt, long daysSinceDelivery) {
    }
}
