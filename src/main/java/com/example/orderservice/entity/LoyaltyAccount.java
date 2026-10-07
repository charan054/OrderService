package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

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
    // Set on every EARNED/REDEEMED/ADJUSTED transaction - the anchor OrderService.applyPointsExpiry() checks a
    // balance against. Null for an account that's never had a transaction, which applyPointsExpiry() treats as
    // "nothing to expire" rather than "infinitely overdue".
    private Instant lastActivityAt;
    // When the "your points are about to expire" email was last sent (see LoyaltyExpiryWarningService). Only counts
    // for the activity window it was sent in: any later activity moves lastActivityAt past it, which re-arms the
    // warning for the next expiry.
    private Instant expiryWarnedAt;

    // A balance with no activity for this long expires entirely (OrderService.applyPointsExpiry).
    public static final int POINTS_EXPIRY_DAYS = 365;

    // Derived, not persisted (no matching column) - Hibernate here uses field-based access, so a getter with no
    // backing field is simply ignored for persistence and only shows up in the JSON response.
    public LoyaltyTier getTier() {
        return LoyaltyTier.forLifetimePoints(lifetimePointsEarned);
    }

    // When the current balance will expire if nothing else happens on the account; null when there is nothing to
    // expire. Derived like the tier above, so it appears in the loyalty JSON without a column.
    public Instant getPointsExpireAt() {
        if (pointsBalance <= 0 || lastActivityAt == null) {
            return null;
        }
        return lastActivityAt.plus(POINTS_EXPIRY_DAYS, java.time.temporal.ChronoUnit.DAYS);
    }

    public Integer getPointsToNextTier() {
        return LoyaltyTier.pointsToNextTier(lifetimePointsEarned);
    }
}
