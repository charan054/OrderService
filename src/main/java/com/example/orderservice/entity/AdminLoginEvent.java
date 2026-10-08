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

/**
 * One admin sign-in attempt, successful or not, kept for 90 days so an owner can see who signed in and from where, and
 * spot guessing. The username is what was typed (shortened, control characters removed), whether or not such an
 * account exists. Never holds a password. Attempts refused by the rate limiter are not recorded - they would let one
 * attacker fill the table.
 */
@Data
@Entity
@Table(name = "admin_login_event", indexes = @Index(columnList = "at"))
public class AdminLoginEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant at;
    @Column(length = 32)
    private String username;
    private boolean success;
    // OK, WRONG_PASSWORD, UNKNOWN_USER or DISABLED (right password, account switched off). Owners only ever see this
    // in the history; the sign-in response itself is the same 401 for all the failures.
    @Column(length = 20)
    private String result;
    @Column(length = 64)
    private String remoteAddr;
}
