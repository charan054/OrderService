package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// One status change waiting to be (or already) sent to one endpoint. state is PENDING, DELIVERED or FAILED (gave up).
@Data
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {
    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long endpointId;
    private long orderId;
    // The OrderStatus name, kept as text so it does not become a native MySQL enum.
    private String status;
    private Instant occurredAt;
    private String state = PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    @Column(length = 300)
    private String lastError;
    private Instant deliveredAt;
}
