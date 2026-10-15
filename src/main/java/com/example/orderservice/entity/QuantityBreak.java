package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// "Buy minQuantity or more of this product and take percentOff off those units." A product can have several tiers
// (3+ save 5%, 10+ save 10%); the highest tier an order reaches applies. Deactivated, never deleted, when retired.
@Data
@Entity
@Table(name = "quantity_break")
public class QuantityBreak {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int productId;
    private int minQuantity;
    private int percentOff;
    private boolean active = true;
    private Instant createdAt;
}
