package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A storefront search term that found no products, with how many times it happened. Only the term is kept
// (no phone, no session), so this says what shoppers want that the catalog lacks, not who asked.
@Data
@Entity
@Table(name = "missed_search")
public class MissedSearch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 60)
    private String term;
    private int times;
    private Instant lastSearchedAt;
}
