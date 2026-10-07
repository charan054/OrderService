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

// A private note staff attach to an order ("customer called, reschedule to Friday"). Internal only: every endpoint
// that reads or writes these needs the service key, and nothing customer-facing (order JSON, invoice, tracking)
// includes them. Append-only history - a note is added or deleted, never edited.
@Data
@Entity
@Table(name = "order_note", indexes = @Index(columnList = "orderId"))
public class OrderNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long orderId;
    @Column(length = 500)
    private String note;
    private Instant createdAt;
}
