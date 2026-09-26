package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

// One row per (couponCode, customerPhno) pair, tracking how many times that customer has successfully used that
// coupon - enforces Coupon.perCustomerLimit. Only ever incremented after a successful charge, same reasoning as
// Coupon.redemptionCount: a failed/declined payment must not consume a redemption.
@Data
@Table(name = "coupon_redemption", uniqueConstraints = @UniqueConstraint(columnNames = {"couponCode", "customerPhno"}))
@Entity
public class CouponRedemption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String couponCode;
    private long customerPhno;
    private int count;
}
