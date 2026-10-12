package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

// One row per (customer, product): the purchase a "time to reorder?" email was last sent about. A newer purchase
// moves the customer's last purchase past lastPurchaseAt, which arms the next reminder.
@Data
@Entity
@Table(name = "reorder_reminder", uniqueConstraints = @UniqueConstraint(columnNames = {"customerPhno", "productId"}))
public class ReorderReminder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private int productId;
    private Instant lastPurchaseAt;
    private Instant remindedAt;
}
