package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * One PhonePe charge made during checkout, written BEFORE the charge so a crash or timeout cannot lose track of money:
 * STARTED (charge sent, outcome unknown) -> CHARGED (money taken) -> COMPLETED (order exists) / ROLLED_BACK (refunded) /
 * ROLLBACK_FAILED (refund failed, see RefundFailure) / DECLINED (definitely not charged). STARTED or CHARGED that never
 * move on are what staff are alerted about (NeedsAttentionService).
 */
@Data
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {
    public enum Status { STARTED, CHARGED, COMPLETED, ROLLED_BACK, ROLLBACK_FAILED, DECLINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private double amount;
    @Column(length = 64)
    private String idempotencyKey;
    private Long paymentTransactionId;
    private Long orderId;
    // varchar, not a native MySQL ENUM, so a new status is not a schema change.
    @Enumerated(EnumType.STRING)
    @Column(length = 20, columnDefinition = "varchar(20)")
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    @Column(length = 300)
    private String resolution;
}
