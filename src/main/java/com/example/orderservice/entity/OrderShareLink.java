package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A read-only link to one order's status and timeline. The random token is the whole credential - whoever holds the link
// can see the status (never the address, items or prices) until it expires or the customer revokes it.
@Data
@Entity
@Table(name = "order_share_link")
public class OrderShareLink {
    @Id
    private String token;
    private long orderId;
    private long customerPhno;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
}
