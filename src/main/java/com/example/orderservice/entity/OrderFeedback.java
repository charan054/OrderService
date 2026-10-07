package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// How a customer rated a delivered order as a whole (not a product - those are ProductService reviews). At most one
// per order. deliveryRating is optional: null when the customer only gave an overall rating.
@Data
@Entity
@Table(name = "order_feedback")
public class OrderFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private long orderId;
    private long customerPhno;
    private int rating;
    private Integer deliveryRating;
    @Column(length = 500)
    private String comment;
    private Instant createdAt;
}
