package com.example.orderservice.service;

import com.example.orderservice.dto.EmailPreferences;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Promotional-email opt-out. Every promotional email (abandoned-cart, restock / price-drop, loyalty expiry) ends with
 * a one-click unsubscribe link; the link carries an HMAC of the phone number, so it works without signing in (the
 * reader has no session when they click it from their inbox) but cannot be forged for someone else's number. The
 * same switch can be flipped from "My account" by the signed-in customer.
 *
 * Emails about the customer's own orders and their sign-in codes are not promotional and are never suppressed.
 */
@Service
public class EmailPreferenceService {
    private final CustomerAccountRepository accounts;
    private final byte[] secret;
    private final String baseUrl;

    public EmailPreferenceService(CustomerAccountRepository accounts,
                                  @Value("${internal.service.api-key}") String secret,
                                  @Value("${email.public-base-url:}") String baseUrl) {
        this.accounts = accounts;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public EmailPreferences get(long phno) {
        return new EmailPreferences(!account(phno).isMarketingOptOut());
    }

    public EmailPreferences set(long phno, boolean marketingEmails) {
        CustomerAccount account = account(phno);
        account.setMarketingOptOut(!marketingEmails);
        accounts.save(account);
        return new EmailPreferences(marketingEmails);
    }

    /** The link-driven opt-out: only a valid token for this exact phone number is accepted. */
    public void unsubscribe(long phno, String token) {
        if (!tokenMatches(phno, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This unsubscribe link is not valid.");
        }
        set(phno, false);
    }

    /** Appended to every promotional email: how to stop them. */
    public String footer(long phno) {
        if (baseUrl.isEmpty()) {
            return "\n--\nYou can stop emails like this any time in the shop under \"My account\".\n";
        }
        return "\n--\nDon't want emails like this? Unsubscribe in one click: " + baseUrl + "/prefs/unsubscribe?phno="
                + phno + "&token=" + token(phno) + "\n(Emails about your own orders and sign-in codes are not affected.)\n";
    }

    public String token(long phno) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(("unsubscribe:" + phno).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private boolean tokenMatches(long phno, String token) {
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(token(phno).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private CustomerAccount account(long phno) {
        return accounts.findById(phno)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No verified account for this number."));
    }
}
