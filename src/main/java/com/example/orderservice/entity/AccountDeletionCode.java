package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * The emailed code that confirms "delete my account": one outstanding row per phone number, only a SHA-256 hash of the
 * 6-digit code stored, short expiry and a cap on wrong guesses - the same shape as CustomerLoginCode, kept separate so
 * a sign-in code can never confirm a deletion (or the other way round).
 */
@Data
@Entity
@Table(name = "account_deletion_code", uniqueConstraints = @UniqueConstraint(name = "uk_account_deletion_code_phno", columnNames = "phno"))
public class AccountDeletionCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private long phno;
    @Column(nullable = false, length = 64)
    private String codeHash;
    private Instant createdAt;
    private Instant expiresAt;
    private int attempts;
}
