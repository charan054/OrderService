package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// A set of products sold at one fixed price. Deactivated (never deleted) when retired, so old orders keep making sense.
@Data
@Table(name = "bundle")
@Entity
public class Bundle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private double bundlePrice;
    private boolean active = true;
    private Instant createdAt;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "bundle_id", nullable = false)
    private List<BundleItem> items = new ArrayList<>();
}
