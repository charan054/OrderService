package com.example.orderservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A GST credit note: the tax reversed when units of an already-invoiced order are cancelled or returned. One
 * document per cancel/return action (a whole-order cancel is one note with a line per product), numbered in its own
 * per-financial-year series (see InvoiceNumberService.nextCreditNoteNumber). Each line is the difference between the
 * invoice's tax before and after the change, so an invoice's original tax minus all its credit notes always equals
 * the invoice as it stands now.
 */
@Data
@Entity
@Table(name = "credit_note", indexes = {@Index(columnList = "orderId"), @Index(columnList = "issuedAt")})
public class CreditNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String number;
    private long orderId;
    // The invoice this reverses.
    @Column(length = 40)
    private String invoiceNumber;
    private Instant issuedAt;
    // CANCELLED or RETURNED. A plain string, not a Hibernate enum, so a new reason is not a MySQL schema change.
    @Column(length = 20)
    private String reason;
    private boolean interState;
    @Column(length = 60)
    private String placeOfSupply;
    private double taxableValue;
    private double cgst;
    private double sgst;
    private double igst;
    // Value reversed including tax.
    private double total;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "credit_note_id")
    private List<CreditNoteLine> lines = new ArrayList<>();
}
