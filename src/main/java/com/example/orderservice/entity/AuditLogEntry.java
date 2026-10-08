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

// One write made with the service key or a named admin account (the admin dashboard or any trusted caller): who
// (actor) / when / what endpoint and how it ended. Deliberately NO query string and NO body - checkout carries a PIN in its query, and bodies hold
// customer data - so the trail says which action happened, not the data it touched. The path does carry ids
// (e.g. /cart/42/ship), which is what makes it useful.
@Data
@Entity
@Table(name = "audit_log", indexes = @Index(columnList = "timestamp"))
public class AuditLogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant timestamp;
    @Column(length = 10)
    private String method;
    @Column(length = 300)
    private String path;
    private int status;
    @Column(length = 64)
    private String remoteAddr;
    // The admin's username, or "service-key" for the shared key. Null on rows from before named accounts existed.
    @Column(length = 32)
    private String actor;
}
