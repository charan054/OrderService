package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

// "Tell me when you stock this": a search that found nothing, saved by a signed-in customer. StockAlertService
// emails them once when a matching product is in stock (notifiedAt), after which the watch is kept but quiet.
@Data
@Entity
@Table(name = "search_watch", uniqueConstraints = @UniqueConstraint(columnNames = {"customerPhno", "term"}))
public class SearchWatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    @Column(nullable = false, length = 60)
    private String term;
    private Instant createdAt;
    private Instant notifiedAt;
}
