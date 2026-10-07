package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * The email a storefront phone number has proven it owns. Bound on the first successful login code, then every
 * later code for that phone goes only to this address - there is no SMS provider here to prove the phone itself,
 * so the first verified email effectively claims the number (an admin can rebind it, see CustomerAuthService).
 */
@Data
@Entity
@Table(name = "customer_account")
public class CustomerAccount {
    @Id
    private long phno;
    private String email;
    private Instant verifiedAt;
    // This customer's personal refer-a-friend code, created the first time they open their referral card (see
    // ReferralService). Unique; null until then.
    @Column(unique = true, length = 16)
    private String referralCode;
    // True once the customer has unsubscribed from promotional emails (abandoned-cart reminders, restock / price-drop
    // alerts, loyalty-expiry warnings). Emails about their own orders and sign-in codes are never affected.
    private boolean marketingOptOut;
}
