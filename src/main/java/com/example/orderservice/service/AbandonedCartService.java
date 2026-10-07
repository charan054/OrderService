package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AbandonedCartResult;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.SavedCartRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Emails a customer once about a cart they left behind. A saved cart qualifies when it hasn't been touched for
 * abandoned-cart.after-hours (default 24) but is not older than abandoned-cart.max-age-days (default 7 - past that
 * it is stale, not abandoned) and hasn't been reminded yet. Any change to the cart (SavedCartService.replace) clears
 * reminderSentAt, so a customer who comes back and leaves again can be reminded again. State only advances after a
 * successful send; a customer with no verified email, or a mail failure, is simply retried on the next run, and a
 * customer who has unsubscribed from promotional email is skipped (see EmailPreferenceService).
 */
@Service
public class AbandonedCartService {
    private static final Logger log = LoggerFactory.getLogger(AbandonedCartService.class);

    private final SavedCartRepository carts;
    private final CustomerAccountRepository customers;
    private final ProductClient productClient;
    private final MailService mailService;
    private final EmailPreferenceService preferences;
    private final Clock clock;
    private final Duration after;
    private final Duration maxAge;

    public AbandonedCartService(SavedCartRepository carts, CustomerAccountRepository customers,
                                ProductClient productClient, MailService mailService, EmailPreferenceService preferences,
                                Clock clock,
                                @Value("${abandoned-cart.after-hours:24}") long afterHours,
                                @Value("${abandoned-cart.max-age-days:7}") long maxAgeDays) {
        this.carts = carts;
        this.customers = customers;
        this.productClient = productClient;
        this.mailService = mailService;
        this.preferences = preferences;
        this.clock = clock;
        this.after = Duration.ofHours(afterHours);
        this.maxAge = Duration.ofDays(maxAgeDays);
    }

    public AbandonedCartResult run() {
        Instant now = clock.instant();
        List<SavedCart> candidates = carts.findByUpdatedAtBetweenAndReminderSentAtIsNull(
                now.minus(maxAge), now.minus(after));
        int sent = 0;
        int noEmail = 0;
        int skipped = 0;
        int optedOut = 0;
        int failed = 0;
        for (SavedCart cart : candidates) {
            if (cart.getLines().isEmpty()) {
                continue;
            }
            CustomerAccount account = customers.findById(cart.getPhno()).orElse(null);
            String email = account == null ? null : account.getEmail();
            if (email == null || email.isBlank()) {
                noEmail++;
                continue;
            }
            if (account.isMarketingOptOut()) {
                optedOut++;
                continue;
            }
            List<String> items = describeItems(cart);
            if (items.isEmpty()) {
                skipped++;
                continue;
            }
            String body = "Hi,\n\nYou left these in your Charan Mart cart:\n\n  - " + String.join("\n  - ", items)
                    + "\n\nSign in to the store and your cart will be waiting. Prices and stock are checked again at "
                    + "checkout, so what you see there is what you pay.\n"
                    + preferences.footer(cart.getPhno());
            if (mailService.send(email, "You left something in your cart", body)) {
                cart.setReminderSentAt(now);
                carts.save(cart);
                sent++;
            } else {
                failed++;
            }
        }
        log.info("Abandoned cart reminders: {} sent, {} without an email, {} skipped, {} unsubscribed, {} send failure(s)",
                sent, noEmail, skipped, optedOut, failed);
        return new AbandonedCartResult(sent, noEmail, skipped, optedOut, failed);
    }

    // "2 x Name" per product that still exists; a product that has gone from the catalog is left out, and a lookup
    // failure (ProductService down) leaves it out too rather than emailing a made-up name.
    private List<String> describeItems(SavedCart cart) {
        List<String> items = new ArrayList<>();
        for (SavedCart.Line line : cart.getLines()) {
            try {
                Product p = productClient.getProductById(line.getProductId());
                if (p != null && p.getProductName() != null) {
                    items.add(line.getQuantity() + " x " + p.getProductName());
                }
            } catch (FeignException e) {
                // not in the catalog (or catalog unreachable) - skip this line
            }
        }
        return items;
    }
}
