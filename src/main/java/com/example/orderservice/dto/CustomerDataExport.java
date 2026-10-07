package com.example.orderservice.dto;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.LoyaltyTransaction;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderFeedback;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.Wishlist;

import java.time.Instant;
import java.util.List;

// Everything this service holds about ONE customer, for them to download (GET /customer/export). Deliberately
// excludes: staff-internal order notes, other people's phone numbers (a referral you made is reported as a count,
// not as the friend's number), and product reviews (those live in ProductService).
public record CustomerDataExport(Instant exportedAt, long phno, Account account, List<Cart> orders,
                                 List<ShippingAddress> addresses, List<Wishlist> wishlist,
                                 List<StockWaitlist> restockWaitlist, LoyaltyAccount loyalty,
                                 List<LoyaltyTransaction> loyaltyHistory, List<ProductQuestion> questions,
                                 List<OrderFeedback> orderFeedback, SavedCart savedCart,
                                 List<NotificationLog> notifications, Referrals referrals) {
    public record Account(String email, Instant emailVerifiedAt, String referralCode) {
    }

    public record Referrals(int friendsReferred, boolean usedAFriendsCode) {
    }
}
