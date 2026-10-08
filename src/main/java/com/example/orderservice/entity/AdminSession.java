package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** A signed-in admin. Only the SHA-256 hash of the bearer token is stored; the role is read from the account on every request. */
@Data
@Entity
@Table(name = "admin_session")
public class AdminSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    private long adminId;
    private Instant createdAt;
    private Instant expiresAt;
}
