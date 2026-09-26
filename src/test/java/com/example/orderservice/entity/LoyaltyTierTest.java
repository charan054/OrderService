package com.example.orderservice.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoyaltyTierTest {

    @Test
    void bronzeIsTheDefaultForNoLifetimePoints() {
        assertEquals(LoyaltyTier.BRONZE, LoyaltyTier.forLifetimePoints(0));
        assertEquals(LoyaltyTier.BRONZE, LoyaltyTier.forLifetimePoints(499));
    }

    @Test
    void silverStartsExactlyAtItsThreshold() {
        assertEquals(LoyaltyTier.SILVER, LoyaltyTier.forLifetimePoints(500));
        assertEquals(LoyaltyTier.SILVER, LoyaltyTier.forLifetimePoints(1999));
    }

    @Test
    void goldStartsExactlyAtItsThreshold() {
        assertEquals(LoyaltyTier.GOLD, LoyaltyTier.forLifetimePoints(2000));
        assertEquals(LoyaltyTier.GOLD, LoyaltyTier.forLifetimePoints(1_000_000));
    }

    @Test
    void pointsToNextTierCountsUpToTheNextThreshold() {
        assertEquals(500, LoyaltyTier.pointsToNextTier(0));
        assertEquals(1, LoyaltyTier.pointsToNextTier(499));
        assertEquals(1500, LoyaltyTier.pointsToNextTier(500));
    }

    @Test
    void pointsToNextTierIsNullAtTheTopTier() {
        assertNull(LoyaltyTier.pointsToNextTier(2000));
        assertNull(LoyaltyTier.pointsToNextTier(1_000_000));
    }
}
