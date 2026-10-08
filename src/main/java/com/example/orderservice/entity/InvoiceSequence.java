package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

// The last invoice number issued in one Indian financial year (April-March, e.g. "2026-27"). See
// InvoiceNumberService.
@Data
@Entity
@Table(name = "invoice_sequence")
public class InvoiceSequence {
    @Id
    @Column(length = 7)
    private String fiscalYear;
    private long lastNumber;
}
