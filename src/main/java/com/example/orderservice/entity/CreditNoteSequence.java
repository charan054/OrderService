package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

// The last credit note number issued in one Indian financial year. Separate from InvoiceSequence because credit
// notes are their own numbered series. See InvoiceNumberService.
@Data
@Entity
@Table(name = "credit_note_sequence")
public class CreditNoteSequence {
    @Id
    @Column(length = 7)
    private String fiscalYear;
    private long lastNumber;
}
