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

/** One browser a customer allowed notifications in. The endpoint is the browser's own push address (unique per browser). */
@Data
@Entity
@Table(name = "push_subscription", indexes = @Index(name = "idx_push_subscription_phno", columnList = "customerPhno"))
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    @Column(nullable = false, unique = true, length = 500)
    private String endpoint;
    @Column(nullable = false, length = 200)
    private String p256dh;
    @Column(nullable = false, length = 100)
    private String auth;
    private Instant createdAt;
}
