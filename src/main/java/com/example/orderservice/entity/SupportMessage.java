package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// One message in a SupportTicket's conversation, from the customer or from the store. Append-only.
@Data
@Entity
@Table(name = "support_message", indexes = @Index(name = "idx_support_message_ticket", columnList = "ticketId"))
public class SupportMessage {
    public enum Author { CUSTOMER, STAFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long ticketId;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(10)", nullable = false)
    private Author author;
    @Column(length = 1000)
    private String body;
    private Instant createdAt;
}
