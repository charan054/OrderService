package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

// One row per customer phone number, same identity model as Cart/Wishlist/ShippingAddress - this system has no
// login, so the phone number itself is the primary key rather than a generated id, since there's exactly one
// balance per customer and no reason to look it up any other way.
@Data
@Table(name = "loyalty_account")
@Entity
public class LoyaltyAccount {
    @Id
    private long customerPhno;
    private int pointsBalance;
}
