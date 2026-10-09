package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// One service's result in one health probe (see HealthHistoryService). Append-only; old rows are purged.
@Data
@Entity
@Table(name = "health_sample")
public class HealthSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Instant checkedAt;
    @Column(nullable = false, length = 40)
    private String serviceName;
    // UP or DOWN, as reported by HealthService.
    @Column(nullable = false, length = 8)
    private String status;
    private long responseMillis;
    @Column(length = 200)
    private String detail;
}
