package com.example.orderservice.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Why orders come back (GET /cart/analytics/reasons, admin): over the orders PLACED between from and to (UTC, inclusive),
 * how many were cancelled or had units returned, the reasons given, a week-by-week series and the products most often
 * sent back. Units are counted per order line, so a part-cancelled or part-returned order is included.
 */
public record ReasonsReport(LocalDate from, LocalDate to, int ordersPlaced, int ordersWithCancellations, int ordersWithReturns,
                            int unitsCancelled, int unitsReturned, List<Count> cancelReasons, List<Count> returnReasons,
                            List<Week> weeks, List<ProductRow> products) {
    public record Count(String reason, int units) {
    }

    /** weekStart is the Monday (UTC) of the week the orders were placed in. */
    public record Week(LocalDate weekStart, int ordersPlaced, int unitsCancelled, int unitsReturned) {
    }

    /** returnRatePercent = (cancelled + returned units) / ordered units. */
    public record ProductRow(int productId, String productName, int unitsOrdered, int unitsCancelled, int unitsReturned,
                             double sentBackPercent) {
    }
}
