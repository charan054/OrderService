package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * A prepaid gift card: a one-time code worth a fixed amount that a customer redeems into their store credit. Like a
 * session token the code is a bearer secret, so only its SHA-256 hash is stored (plus the last four characters for
 * the admin list); the plain code exists once, in the response that minted it.
 */
@Data
@Entity
@Table(name = "gift_card")
public class GiftCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String codeHash;
    @Column(length = 4)
    private String last4;
    private double amount;
    private Instant createdAt;
    // The admin's username, or "service-key".
    @Column(length = 32)
    private String createdBy;
    @Column(length = 100)
    private String note;
    // Null = never expires.
    private Instant expiresAt;
    private Long redeemedBy;
    private Instant redeemedAt;
    // Cancelled by an owner before it was used.
    private boolean voided;
    private Instant voidedAt;
}
