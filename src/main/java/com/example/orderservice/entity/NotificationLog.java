package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

// An audit trail of customer-facing notifications dispatched off the Kafka order-notification topic (see
// OrderKafkaConsumer) - not the internal log-only messages every order/cancel/ship/deliver/return already
// publishes there, just the subset (SHIPPED/DELIVERED) meant for the customer. This system has no email/SMS
// provider wired up, so "dispatched" here means logged + recorded, not actually delivered anywhere yet.
@Data
@Table(name = "notification_log")
@Entity
public class NotificationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private OrderStatus eventType;
    private String message;
    private Instant sentAt;
}
