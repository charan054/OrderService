package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One outstanding storefront login code. Only a SHA-256 hash of the 6-digit code is stored, same reasoning as
 * Bankapplication's PinResetOtp. email is where it was sent - for a not-yet-bound phone that's the address the
 * customer typed, and it becomes the bound address once the code is verified.
 */
@Data
@Entity
@Table(name = "customer_login_code")
public class CustomerLoginCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(unique = true)
    private long phno;
    @Column(nullable = false, length = 64)
    private String codeHash;
    private String email;
    private Instant createdAt;
    private Instant expiresAt;
    // Wrong guesses against this one code - verify() gives up past MAX_VERIFY_ATTEMPTS so a 6-digit code can't be
    // brute-forced inside its expiry window.
    private int attempts;
}
