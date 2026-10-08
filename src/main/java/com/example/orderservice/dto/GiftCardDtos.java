package com.example.orderservice.dto;

import java.time.Instant;
import java.util.List;

/** Request/response shapes for gift cards (see GiftCardService). */
public final class GiftCardDtos {
    private GiftCardDtos() {
    }

    public record MintRequest(int count, double amount, String note, Integer expiresInDays) {
    }

    /** code is the full plain code - shown once, here, and never again. */
    public record MintedCard(long id, String code, String last4, double amount, Instant expiresAt) {
    }

    public record MintResult(List<MintedCard> cards, double totalValue) {
    }

    public record RedeemRequest(long phno, String code) {
    }

    public record RedeemResult(double amount, double balance) {
    }

    /** status: ACTIVE, REDEEMED, EXPIRED or VOIDED. */
    public record GiftCardView(long id, String last4, double amount, String status, Instant createdAt, String createdBy,
                               String note, Instant expiresAt, Long redeemedBy, Instant redeemedAt) {
    }

    /** outstanding* is the value of cards that could still be redeemed - money the store owes in goods. */
    public record GiftCardList(int outstandingCards, double outstandingValue, List<GiftCardView> cards) {
    }
}
