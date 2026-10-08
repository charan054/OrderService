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

/**
 * "Subscribe and save": one product, delivered again every intervalDays as a cash-on-delivery order at a small
 * discount (the percent is copied onto the subscription when it is created, so a later change to the store's setting
 * does not change what an existing subscriber was promised). status is a plain string (ACTIVE / PAUSED / CANCELLED)
 * rather than a Java enum so a new state later is only a code change, never a column change.
 */
@Data
@Entity
@Table(name = "subscription", indexes = {@Index(name = "idx_subscription_phno", columnList = "customerPhno"),
        @Index(name = "idx_subscription_due", columnList = "status, nextRunAt")})
public class Subscription {
    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    // Printed on each order's invoice; copied from the sign-up request.
    @Column(length = 100)
    private String customerName;
    private int productId;
    private int quantity;
    private int intervalDays;
    private Long shippingAddressId;
    private double discountPercent;
    @Column(length = 12, nullable = false)
    private String status = ACTIVE;
    // When the next order is due; the scheduler places it once this has passed.
    private Instant nextRunAt;
    private Long lastOrderId;
    private int ordersPlaced;
    // Orders that could not be placed in a row (out of stock, cash on delivery refused, ...). Three pause the subscription.
    private int consecutiveFailures;
    @Column(length = 200)
    private String lastError;
    private Instant createdAt;
}
