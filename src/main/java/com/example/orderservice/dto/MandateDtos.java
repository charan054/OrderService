package com.example.orderservice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** The shop's view of PhonepayService's payment mandates (a customer's standing permission to charge their PhonePe account). */
public final class MandateDtos {
    private MandateDtos() {
    }

    /** Mirrors PhonepayService's MandateDtos.View. */
    public record View(long id, long payerPhno, String merchant, BigDecimal perChargeLimit, BigDecimal monthlyLimit,
                       BigDecimal chargedThisMonth, BigDecimal remainingThisMonth, Instant validUntil, String status,
                       Instant createdAt, Instant lastChargeAt) {
    }

    /** Sent to PhonepayService to set a mandate up (with the customer's own PhonePe session). */
    public record CreateRequest(BigDecimal perChargeLimit, BigDecimal monthlyLimit, Integer validDays, String pin) {
    }

    /** What the storefront posts: the customer's phone number + PIN (checked by PhonePe, never stored) and the limits they chose. */
    public record SetUp(long phno, String pin, BigDecimal perChargeLimit, BigDecimal monthlyLimit, Integer validDays) {
    }

    /** Sent to PhonepayService to charge a mandate; the key makes a retry the same charge, not a second one. */
    public record ChargeRequest(BigDecimal amount, String idempotencyKey, String note) {
    }
}
