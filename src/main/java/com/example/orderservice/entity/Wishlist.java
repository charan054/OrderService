package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "wishlist", uniqueConstraints = @UniqueConstraint(columnNames = {"customerPhno", "productId"}))
public class Wishlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private int productId;
    // Snapshot of the product's price at the moment it was wishlisted - compared against its current price by
    // OrderService.getPriceDropAlerts() to detect a drop. Null for a wishlist entry saved before this field
    // existed; such an entry is simply skipped by the price-drop check rather than treated as a false drop.
    private Double priceWhenAdded;
    // The lowest price a price-drop email has already been sent for (see StockAlertService) - only a price below
    // BOTH priceWhenAdded and this triggers another email. Reset to null once the price recovers to the added
    // price or above, so a later drop alerts again.
    private Double lastAlertedPrice;
}
