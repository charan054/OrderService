package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// Append-only audit trail behind LoyaltyAccount.pointsBalance, same "why" role PriceHistory/CouponRedemption
// play elsewhere in this codebase - the balance is the current total, this is how it got there. orderId is null
// for an admin ADJUSTED correction, since those aren't tied to any particular order.
@Data
@Table(name = "loyalty_transaction")
@Entity
public class LoyaltyTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private Long orderId;
    // Positive for EARNED/a credit ADJUSTED, negative for REDEEMED/a debit ADJUSTED.
    private int points;
    // columnDefinition pins this to a plain VARCHAR - see Cart.status for why a native MySQL ENUM column would
    // break the moment a new constant is ever added here.
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private LoyaltyTransactionType type;
    private String reason;
    private Instant timestamp;
}
