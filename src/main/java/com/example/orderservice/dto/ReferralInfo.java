package com.example.orderservice.dto;

// A customer's own referral card: the code to share, how many friends used it and how many of those were rewarded,
// the points earned so far, what each side gets, the minimum order that qualifies, and whether THIS customer has
// already used someone else's code.
public record ReferralInfo(String code, int friendsReferred, int friendsRewarded, int pointsEarned,
                           int bonusPoints, double minimumOrderAmount, boolean usedAFriendsCode) {
}
