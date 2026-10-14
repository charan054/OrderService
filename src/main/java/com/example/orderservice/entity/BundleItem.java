package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Table(name = "bundle_item")
@Entity
public class BundleItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int productId;
    private int quantity;
}
