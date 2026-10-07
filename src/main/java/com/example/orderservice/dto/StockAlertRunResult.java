package com.example.orderservice.dto;

// What one StockAlertService run did: emails actually sent (one per customer), how many restock / price-drop alerts
// they carried, customers owed an alert but with no verified email, customers owed one who have unsubscribed, and
// sends that failed (retried next run).
public record StockAlertRunResult(int emailsSent, int restockAlerts, int priceDropAlerts, int customersWithoutEmail,
                                  int optedOut, int sendFailures) {
}
