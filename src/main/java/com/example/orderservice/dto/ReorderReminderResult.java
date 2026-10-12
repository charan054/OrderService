package com.example.orderservice.dto;

// Outcome of one "time to reorder?" run: emails sent (one per customer), products mentioned in them, and why
// others were skipped.
public record ReorderReminderResult(int emailsSent, int productsMentioned, int customersWithoutEmail, int optedOut,
                                    int sendFailures) {
}
