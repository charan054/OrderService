package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "coupon")
public class Coupon {
    // The code itself is the natural key - callers look coupons up by code, never by a synthetic id.
    @Id
    private String code;
    private double discountPercent;
    private boolean active = true;
}
