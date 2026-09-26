package com.example.orderservice.entity;

// Tier is derived purely from LoyaltyAccount.lifetimePointsEarned - never from the current spendable balance,
// so redeeming points never demotes a customer. Thresholds/multipliers live here (not in OrderService) so the
// tier ladder is defined and tested in one place.
public enum LoyaltyTier {
    BRONZE(0, 1.0),
    SILVER(500, 1.25),
    GOLD(2000, 1.5);

    private final int lifetimePointsThreshold;
    private final double earnMultiplier;

    LoyaltyTier(int lifetimePointsThreshold, double earnMultiplier) {
        this.lifetimePointsThreshold = lifetimePointsThreshold;
        this.earnMultiplier = earnMultiplier;
    }

    public int getLifetimePointsThreshold() {
        return lifetimePointsThreshold;
    }

    public double getEarnMultiplier() {
        return earnMultiplier;
    }

    public static LoyaltyTier forLifetimePoints(long lifetimePointsEarned) {
        LoyaltyTier current = BRONZE;
        for (LoyaltyTier tier : values()) {
            if (lifetimePointsEarned >= tier.lifetimePointsThreshold) {
                current = tier;
            }
        }
        return current;
    }

    // Null once already at the top tier - there's nothing further to progress toward.
    public static Integer pointsToNextTier(long lifetimePointsEarned) {
        LoyaltyTier current = forLifetimePoints(lifetimePointsEarned);
        LoyaltyTier[] all = values();
        int nextOrdinal = current.ordinal() + 1;
        if (nextOrdinal >= all.length) {
            return null;
        }
        return (int) (all[nextOrdinal].lifetimePointsThreshold - lifetimePointsEarned);
    }
}
