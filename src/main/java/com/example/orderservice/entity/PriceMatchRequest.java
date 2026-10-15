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

// A customer saying "this product is cheaper elsewhere" with the price and a link. ourPrice is the live price when they
// asked. Approving is the store's decision only - nothing here changes a price (the admin can start a sale for it).
@Data
@Entity
@Table(name = "price_match_request")
public class PriceMatchRequest {
    public enum Status { OPEN, APPROVED, DECLINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int productId;
    private long customerPhno;
    private double competitorPrice;
    private double ourPrice;
    @Column(length = 500)
    private String competitorUrl;
    // Stored as VARCHAR(16), not a native MySQL ENUM, so new states never need a column change.
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;
    @Column(length = 300)
    private String adminNote;
    private Instant createdAt;
    private Instant resolvedAt;
    // Set when approving also started a sale on the product: the sale price and when it ends.
    private Double salePrice;
    private Instant saleEndsAt;
}
