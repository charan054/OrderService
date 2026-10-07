package com.example.orderservice.dto;

import java.util.List;
import java.util.Map;

// Why orders get cancelled (GET /cart/analytics/cancellations, admin). byReason holds every allowed reason code
// (zero when unused) plus NOT_GIVEN for cancellations with no reason - older ones, per-item cancels that emptied an
// order, and system cancels such as an expired UPI payment. recentNotes are the latest free-text notes.
public record CancellationReport(int totalCancelled, Map<String, Integer> byReason, List<Note> recentNotes) {
    public record Note(long orderId, String reason, String note) {
    }
}
