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

/**
 * A customer's "problem with this order" request (see SupportTicketService). The conversation itself is in
 * SupportMessage; this row holds what it is about and where it stands: OPEN = waiting on the store, ANSWERED =
 * the store replied and is waiting on the customer, RESOLVED = closed (a customer reply re-opens it).
 */
@Data
@Entity
@Table(name = "support_ticket", indexes = {
        @Index(name = "idx_support_ticket_phno", columnList = "customerPhno"),
        @Index(name = "idx_support_ticket_order", columnList = "orderId")})
public class SupportTicket {
    public enum Category { DAMAGED, MISSING_ITEM, WRONG_ITEM, NOT_DELIVERED, PAYMENT, OTHER }

    public enum Status { OPEN, ANSWERED, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private long orderId;
    // Optional: the one product in the order the problem is about.
    private Integer productId;
    // VARCHAR, not a native MySQL ENUM, so new constants need no manual ALTER (see Cart.status).
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", nullable = false)
    private Category category;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(12)", nullable = false)
    private Status status;
    // Optional link to a photo the customer hosts elsewhere (there is no upload storage here). Shown to staff as a
    // link, never embedded.
    @Column(length = 500)
    private String photoUrl;
    @Column(length = 500)
    private String resolutionNote;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
}
