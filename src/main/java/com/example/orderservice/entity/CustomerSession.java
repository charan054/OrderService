package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** A logged-in storefront session. Only the SHA-256 hash of the bearer token is stored. */
@Data
@Entity
@Table(name = "customer_session")
public class CustomerSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    private long phno;
    private Instant createdAt;
    private Instant expiresAt;
}
