package com.example.orderservice.dto;

// What a customer controls about the email they get. Order emails and sign-in codes are not optional, so the only
// switch is promotional email (abandoned-cart reminders, restock / price-drop alerts, loyalty-expiry warnings).
public record EmailPreferences(boolean marketingEmails) {
}
