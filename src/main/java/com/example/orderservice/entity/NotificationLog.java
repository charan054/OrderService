package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

// An audit trail of customer-facing notifications (SHIPPED/DELIVERED), written by CustomerNotifier. emailed says
// whether it actually reached the customer's verified email; false means recorded only (no verified email on
// file yet, or the mail server was unreachable). The recipient address itself is deliberately not stored here -
// the per-order notification list is public by order id.
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
    private boolean emailed;
}
