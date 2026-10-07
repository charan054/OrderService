package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * An admin's decision about cash on delivery for one phone number, overriding the automatic rules in CodRiskService:
 * ALLOW always permits it (e.g. a trusted regular whose cancellations were the store's fault), BLOCK always refuses
 * it. No row means the automatic rules apply.
 */
@Data
@Entity
@Table(name = "cod_override")
public class CodOverride {
    public enum Mode { ALLOW, BLOCK }

    @Id
    private long phno;
    // VARCHAR, not a native MySQL ENUM, so a constant added later doesn't need a manual ALTER (see Cart.status).
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(10)", nullable = false)
    private Mode mode;
    @Column(length = 200)
    private String note;
    private Instant updatedAt;
}
