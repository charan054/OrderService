package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A customer's request to swap some delivered units of one item for another product (see ExchangeService). Status is a
// plain string: REQUESTED -> APPROVED -> RECEIVED, or REJECTED / CANCELLED.
@Data
@Entity
@Table(name = "exchange_request")
public class ExchangeRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long orderId;
    private long customerPhno;
    private int productId;
    private int quantity;
    private int replacementProductId;
    @Column(length = 300)
    private String reason;
    @Column(length = 20)
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
