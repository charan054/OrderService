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
    // Cumulative points ever EARNED (never reduced by redemption) - the sole basis for tier below. An admin
    // ADJUSTED correction deliberately does NOT touch this: a goodwill credit isn't spending, so it must not
    // let someone game their way into a higher tier.
    private long lifetimePointsEarned;

    // Derived, not persisted (no matching column) - Hibernate here uses field-based access, so a getter with no
    // backing field is simply ignored for persistence and only shows up in the JSON response.
    public LoyaltyTier getTier() {
        return LoyaltyTier.forLifetimePoints(lifetimePointsEarned);
    }

    public Integer getPointsToNextTier() {
        return LoyaltyTier.pointsToNextTier(lifetimePointsEarned);
    }
}
