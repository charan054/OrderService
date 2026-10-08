package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** One product's share of a CreditNote. */
@Data
@Entity
@Table(name = "credit_note_line")
public class CreditNoteLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int productId;
    @Column(length = 200)
    private String productName;
    @Column(length = 16)
    private String hsnCode;
    private double gstRate;
    private int quantity;
    private double taxableValue;
    private double cgst;
    private double sgst;
    private double igst;
    private double total;
}
