package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "coupon")
public class Coupon {
    // The code itself is the natural key - callers look coupons up by code, never by a synthetic id.
    @Id
    private String code;
    private double discountPercent;
    private boolean active = true;
    // Null means no expiry. Checked in OrderService.resolveDiscount() alongside active - an expired coupon must
    // fail the same way a deactivated one does, before any payment is attempted.
    private Instant expiryDate;
    // Null means unlimited. Incremented only after a successful charge (see OrderService.order()), never at
    // validation time - a failed/declined payment must not consume a redemption.
    private Integer maxRedemptions;
    private int redemptionCount;
    // Null means unlimited per customer. Enforced via CouponRedemption, one row per (code, customerPhno).
    private Integer perCustomerLimit;
}
