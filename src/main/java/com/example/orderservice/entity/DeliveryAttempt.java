package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// One failed try at handing a shipped order over (see DeliveryFailureService).
@Data
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long orderId;
    private int attemptNo;
    @Column(length = 30)
    private String reason;
    @Column(length = 200)
    private String note;
    private Instant createdAt;
}
