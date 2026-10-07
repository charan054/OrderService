package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.StockAlertRunResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.WishlistRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emails customers when a product on their waitlist is back in stock and when a wishlisted product gets cheaper.
 * Until now both were only visible if the customer opened the shop and looked ("computed on demand, no scheduler");
 * StockAlertScheduler now runs this periodically, and POST /cart/alerts/run triggers it by hand.
 *
 * One digest email per customer per run (not one per product), sent to the email the customer verified at sign-in.
 * State is only advanced after a send actually succeeds - a customer with no verified email, or a mail server that
 * is down, is simply retried on the next run - and each restock / each new lower price is announced once.
 */
@Service
public class StockAlertService {
    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);

    private final WishlistRepository wishlists;
    private final StockWaitlistRepository waitlists;
    private final CustomerAccountRepository accounts;
    private final ProductClient productClient;
    private final MailService mailService;
    private final Clock clock;
    private final String shopUrl;

    public StockAlertService(WishlistRepository wishlists, StockWaitlistRepository waitlists,
                             CustomerAccountRepository accounts, ProductClient productClient, MailService mailService,
                             Clock clock, @Value("${alerts.shop-url:}") String shopUrl) {
        this.wishlists = wishlists;
        this.waitlists = waitlists;
        this.accounts = accounts;
        this.productClient = productClient;
        this.mailService = mailService;
        this.clock = clock;
        this.shopUrl = shopUrl == null ? "" : shopUrl.trim();
    }

    // What one customer is owed this run, plus what to record once their email has really gone out.
    private static final class Pending {
        final List<String> lines = new ArrayList<>();
        final List<Runnable> onSent = new ArrayList<>();
        int restocks;
        int priceDrops;
    }

    public StockAlertRunResult run() {
        Map<Integer, Product> products = new HashMap<>();
        Map<Long, Pending> byCustomer = new LinkedHashMap<>();

        for (StockWaitlist entry : waitlists.findAll()) {
            Product product = lookup(products, entry.getProductId());
            if (product == null) {
                continue;
            }
            if (product.getProductStock() <= 0) {
                if (entry.getNotifiedAt() != null) {
                    // Sold out again: arm the next restock email.
                    entry.setNotifiedAt(null);
                    waitlists.save(entry);
                }
                continue;
            }
            if (entry.getNotifiedAt() == null) {
                Pending pending = byCustomer.computeIfAbsent(entry.getCustomerPhno(), k -> new Pending());
                pending.restocks++;
                pending.lines.add("Back in stock: " + product.getProductName() + " - Rs. " + money(product.getProductPrice())
                        + " (" + product.getProductStock() + " available)");
                pending.onSent.add(() -> {
                    entry.setNotifiedAt(clock.instant());
                    waitlists.save(entry);
                });
            }
        }

        for (Wishlist item : wishlists.findAll()) {
            if (item.getPriceWhenAdded() == null) {
                continue;
            }
            Product product = lookup(products, item.getProductId());
            if (product == null) {
                continue;
            }
            double price = product.getProductPrice();
            if (price >= item.getPriceWhenAdded()) {
                if (item.getLastAlertedPrice() != null) {
                    // Recovered: the next drop should alert again.
                    item.setLastAlertedPrice(null);
                    wishlists.save(item);
                }
                continue;
            }
            double baseline = item.getLastAlertedPrice() == null
                    ? item.getPriceWhenAdded() : Math.min(item.getPriceWhenAdded(), item.getLastAlertedPrice());
            if (price < baseline) {
                Pending pending = byCustomer.computeIfAbsent(item.getCustomerPhno(), k -> new Pending());
                pending.priceDrops++;
                pending.lines.add("Price drop: " + product.getProductName() + " is now Rs. " + money(price)
                        + " (was Rs. " + money(item.getPriceWhenAdded()) + ")");
                pending.onSent.add(() -> {
                    item.setLastAlertedPrice(price);
                    wishlists.save(item);
                });
            }
        }

        int emails = 0;
        int restocks = 0;
        int priceDrops = 0;
        int skippedNoEmail = 0;
        int failed = 0;
        for (Map.Entry<Long, Pending> entry : byCustomer.entrySet()) {
            Pending pending = entry.getValue();
            String email = accounts.findById(entry.getKey()).map(CustomerAccount::getEmail).orElse(null);
            if (email == null || email.isBlank()) {
                skippedNoEmail++;
                continue;
            }
            if (mailService.send(email, subjectFor(pending), bodyFor(pending))) {
                pending.onSent.forEach(Runnable::run);
                emails++;
                restocks += pending.restocks;
                priceDrops += pending.priceDrops;
            } else {
                failed++;
            }
        }
        log.info("Stock alerts: {} email(s) sent ({} restock, {} price drop), {} customer(s) without an email, {} send failure(s)",
                emails, restocks, priceDrops, skippedNoEmail, failed);
        return new StockAlertRunResult(emails, restocks, priceDrops, skippedNoEmail, failed);
    }

    // One catalog lookup per product per run; null (cached too) when the product no longer exists.
    private Product lookup(Map<Integer, Product> cache, int productId) {
        if (cache.containsKey(productId)) {
            return cache.get(productId);
        }
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            product = null;
        }
        cache.put(productId, product);
        return product;
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String subjectFor(Pending pending) {
        if (pending.restocks > 0 && pending.priceDrops > 0) {
            return "Items you wanted are back in stock or cheaper";
        }
        return pending.restocks > 0 ? "An item you wanted is back in stock" : "An item on your wishlist just got cheaper";
    }

    private String bodyFor(Pending pending) {
        StringBuilder body = new StringBuilder("Hi,\n\nGood news from Charan Mart:\n\n");
        pending.lines.forEach(line -> body.append("  - ").append(line).append('\n'));
        body.append('\n');
        if (!shopUrl.isEmpty()) {
            body.append("Shop now: ").append(shopUrl).append("\n\n");
        }
        body.append("Stock can sell out quickly. You are getting this because the item is on your waitlist or wishlist; "
                + "remove it in the shop under \"My account\" to stop these emails.\n");
        return body.toString();
    }
}
