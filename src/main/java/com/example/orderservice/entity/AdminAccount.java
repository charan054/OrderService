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

/** A named person who signs in to the admin dashboard (cart.html). Only a BCrypt hash of the password is stored. */
@Data
@Entity
@Table(name = "admin_account")
public class AdminAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 32)
    private String username;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    // VARCHAR, not Hibernate's default native MySQL ENUM, so a new role later is not a schema change.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private AdminRole role;
    // A disabled account keeps its history (the audit log names it) but cannot sign in, and its sessions stop working.
    private boolean active = true;
    private Instant createdAt;
    // "service-key" or the owner's username - who created the account.
    @Column(length = 32)
    private String createdBy;
    private Instant lastLoginAt;
    // Wrong-password (or switched-off account) attempts since the last successful sign-in, and when the latest was -
    // so an owner can see an account being guessed at. Reset by a successful sign-in.
    private int failedLogins;
    private Instant lastFailedLoginAt;
}
