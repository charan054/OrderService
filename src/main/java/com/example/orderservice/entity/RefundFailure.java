package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// Money the shop owes a customer that the system failed to hand back on its own (today: while undoing a checkout whose
// stock step failed after the buyer was charged). Staff-only; stays open until someone settles it and resolves it.
@Data
@Entity
@Table(name = "refund_failure", indexes = @Index(name = "idx_refund_failure_resolved", columnList = "resolvedAt"))
public class RefundFailure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long orderId;
    private long customerPhno;
    // PHONEPE_REFUND or STORE_CREDIT - a plain string so a new kind is not a schema change.
    @Column(length = 20)
    private String kind;
    private double amount;
    private Long paymentTransactionId;
    @Column(length = 300)
    private String reason;
    private Instant createdAt;
    private Instant resolvedAt;
    @Column(length = 300)
    private String resolution;
}
