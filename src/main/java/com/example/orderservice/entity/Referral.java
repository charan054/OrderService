package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * One friend (referee) brought in by one customer's referral code (referrer). A referee can be referred only once
 * (unique refereePhno). The bonus is paid to both sides when the referee's first qualifying order is DELIVERED -
 * see ReferralService - and rewarded flips to true exactly once, before the points are credited, so a retry can
 * never pay twice.
 */
@Data
@Entity
@Table(name = "referral", uniqueConstraints = @UniqueConstraint(columnNames = {"refereePhno"}))
public class Referral {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long referrerPhno;
    private long refereePhno;
    private Instant createdAt;
    private boolean rewarded;
    private Instant rewardedAt;
    // The delivered order that triggered the reward, and the points each side received.
    private Long rewardOrderId;
    private int bonusPoints;
}
