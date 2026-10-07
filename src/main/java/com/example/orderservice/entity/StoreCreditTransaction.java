package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// Append-only audit trail of a store credit balance: amount is signed (+ credit, - spend) and balanceAfter is the
// balance right after it, so the history can be read without recomputing anything.
@Data
@Entity
@Table(name = "store_credit_transaction", indexes = @Index(name = "idx_store_credit_tx_phno", columnList = "phno"))
public class StoreCreditTransaction {
    public enum Type {
        // Refund of an order paid to the wallet (customer chose store credit, or collected cash), or the store
        // credit an order used coming back when it is cancelled/returned.
        REFUND,
        // Used at checkout.
        SPENT,
        // A checkout that reserved credit but whose payment then failed - the credit is given straight back.
        REVERSED,
        // Admin correction or goodwill credit.
        ADJUSTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long phno;
    // VARCHAR, not a native MySQL ENUM (see Cart.status).
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(12)", nullable = false)
    private Type type;
    private double amount;
    private double balanceAfter;
    private Long orderId;
    @Column(length = 200)
    private String note;
    private Instant createdAt;
}
