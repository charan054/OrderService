package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A customer's question about a product, and the admin's answer once there is one (answer == null means still
// pending, and not shown on the storefront). askerPhno is never exposed publicly - see PublicQuestion.
@Data
@Entity
@Table(name = "product_question")
public class ProductQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int productId;
    private long askerPhno;
    @Column(length = 500)
    private String question;
    @Column(length = 1000)
    private String answer;
    private Instant createdAt;
    private Instant answeredAt;
}
