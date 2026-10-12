package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ReorderReminderResult;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ReorderReminder;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.ReorderReminderRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Time to reorder?": for a product a customer keeps buying (at least twice, in different orders), works out their
 * usual gap between purchases and, when they are about due again, emails one short list per customer. Each purchase
 * is nudged once (ReorderReminder), only while the product is in stock, and never to anyone who opted out of
 * marketing email. Orders that were cancelled or returned as a whole, and units cancelled or returned one by one,
 * do not count as purchases. An order is dated by its first tracking event.
 */
@Service
public class ReorderReminderService {
    private static final Logger log = LoggerFactory.getLogger(ReorderReminderService.class);
    static final int MIN_PURCHASES = 2;
    static final double MIN_GAP_DAYS = 3;
    static final double MAX_GAP_DAYS = 180;
    // Nudge a little before the usual date, and stop nagging once they are well past it (they probably moved on).
    static final double EARLY_FACTOR = 0.9;
    static final double GIVE_UP_FACTOR = 3.0;
    static final int MAX_PRODUCTS_PER_EMAIL = 5;

    /** One product a customer is about due to buy again. */
    record Due(long phno, int productId, Instant lastPurchaseAt, double usualGapDays) {
    }

    private final CartRepository carts;
    private final TrackingEventRepository tracking;
    private final ReorderReminderRepository reminders;
    private final CustomerAccountRepository accounts;
    private final ProductClient productClient;
    private final MailService mailService;
    private final EmailPreferenceService preferences;
    private final Clock clock;
    private final String shopUrl;

    public ReorderReminderService(CartRepository carts, TrackingEventRepository tracking, ReorderReminderRepository reminders,
                                  CustomerAccountRepository accounts, ProductClient productClient, MailService mailService,
                                  EmailPreferenceService preferences, Clock clock, @Value("${alerts.shop-url:}") String shopUrl) {
        this.carts = carts;
        this.tracking = tracking;
        this.reminders = reminders;
        this.accounts = accounts;
        this.productClient = productClient;
        this.mailService = mailService;
        this.preferences = preferences;
        this.clock = clock;
        this.shopUrl = shopUrl == null ? "" : shopUrl.trim();
    }

    public ReorderReminderResult run() {
        Instant now = clock.instant();
        List<Due> due = findDue(carts.findAll(), tracking.findAll(), now);
        Map<Long, List<Due>> byCustomer = new TreeMap<>();
        for (Due d : due) {
            ReorderReminder sent = reminders.findByCustomerPhnoAndProductId(d.phno(), d.productId()).orElse(null);
            if (sent != null && sent.getLastPurchaseAt() != null && !d.lastPurchaseAt().isAfter(sent.getLastPurchaseAt())) {
                continue; // already nudged about this purchase
            }
            byCustomer.computeIfAbsent(d.phno(), k -> new ArrayList<>()).add(d);
        }

        Map<Integer, Product> catalog = new HashMap<>();
        int emails = 0, mentioned = 0, noEmail = 0, optedOut = 0, failed = 0;
        for (Map.Entry<Long, List<Due>> entry : byCustomer.entrySet()) {
            CustomerAccount account = accounts.findById(entry.getKey()).orElse(null);
            String email = account == null ? null : account.getEmail();
            if (email == null || email.isBlank()) {
                noEmail++;
                continue;
            }
            if (account.isMarketingOptOut()) {
                optedOut++;
                continue;
            }
            List<Due> lines = new ArrayList<>();
            List<Product> products = new ArrayList<>();
            for (Due d : entry.getValue()) {
                if (lines.size() >= MAX_PRODUCTS_PER_EMAIL) {
                    break;
                }
                Product p = lookup(catalog, d.productId());
                if (p != null && p.getProductStock() > 0) {
                    lines.add(d);
                    products.add(p);
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            if (mailService.send(email, subjectFor(products), bodyFor(lines, products, entry.getKey()))) {
                for (Due d : lines) {
                    ReorderReminder row = reminders.findByCustomerPhnoAndProductId(d.phno(), d.productId()).orElseGet(() -> {
                        ReorderReminder fresh = new ReorderReminder();
                        fresh.setCustomerPhno(d.phno());
                        fresh.setProductId(d.productId());
                        return fresh;
                    });
                    row.setLastPurchaseAt(d.lastPurchaseAt());
                    row.setRemindedAt(now);
                    reminders.save(row);
                }
                emails++;
                mentioned += lines.size();
            } else {
                failed++;
            }
        }
        log.info("Reorder reminders: {} email(s) sent ({} product(s)), {} customer(s) without an email, {} unsubscribed, {} send failure(s)",
                emails, mentioned, noEmail, optedOut, failed);
        return new ReorderReminderResult(emails, mentioned, noEmail, optedOut, failed);
    }

    /** Pure: who is about due to buy what again, most overdue first within each customer. */
    static List<Due> findDue(List<Cart> orders, List<TrackingEvent> events, Instant now) {
        Map<Long, Instant> placedAt = new HashMap<>();
        for (TrackingEvent e : events) {
            if (e.getOrderId() != null && e.getTimestamp() != null) {
                placedAt.merge(e.getOrderId(), e.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        // (customer, product) -> purchase instants, one per order.
        Map<String, List<Instant>> purchases = new HashMap<>();
        Map<String, long[]> keys = new HashMap<>();
        for (Cart order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.RETURNED
                    || order.getOrderItems() == null) {
                continue;
            }
            Instant when = placedAt.get(order.getOrderId());
            if (when == null || order.getCustomerPhno() <= 0) {
                continue;
            }
            for (OrderItem item : order.getOrderItems()) {
                if (item.getOutstandingQuantity() > 0) {
                    String key = order.getCustomerPhno() + ":" + item.getProductId();
                    purchases.computeIfAbsent(key, k -> new ArrayList<>()).add(when);
                    keys.putIfAbsent(key, new long[]{order.getCustomerPhno(), item.getProductId()});
                }
            }
        }
        List<Due> due = new ArrayList<>();
        for (Map.Entry<String, List<Instant>> e : purchases.entrySet()) {
            List<Instant> times = e.getValue().stream().sorted().toList();
            if (times.size() < MIN_PURCHASES) {
                continue;
            }
            double totalDays = 0;
            for (int i = 1; i < times.size(); i++) {
                totalDays += Duration.between(times.get(i - 1), times.get(i)).toSeconds() / 86400.0;
            }
            double gap = totalDays / (times.size() - 1);
            if (gap < MIN_GAP_DAYS || gap > MAX_GAP_DAYS) {
                continue;
            }
            Instant last = times.get(times.size() - 1);
            double sinceLast = Duration.between(last, now).toSeconds() / 86400.0;
            if (sinceLast >= gap * EARLY_FACTOR && sinceLast <= gap * GIVE_UP_FACTOR) {
                long[] k = keys.get(e.getKey());
                due.add(new Due(k[0], (int) k[1], last, gap));
            }
        }
        due.sort(Comparator.comparingLong(Due::phno).thenComparing(Due::lastPurchaseAt));
        return due;
    }

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

    private static String subjectFor(List<Product> products) {
        return products.size() == 1 ? "Time to reorder " + products.get(0).getProductName() + "?"
                : "Time to restock your usuals?";
    }

    private String bodyFor(List<Due> lines, List<Product> products, long phno) {
        StringBuilder body = new StringBuilder("Hi,\n\nBased on how often you buy these, you may be running low:\n\n");
        for (int i = 0; i < lines.size(); i++) {
            Product p = products.get(i);
            body.append("  - ").append(p.getProductName()).append(" - Rs. ")
                    .append(String.format(Locale.ROOT, "%.2f", p.getProductPrice()))
                    .append(" (about every ").append(Math.round(lines.get(i).usualGapDays())).append(" days)");
            if (!shopUrl.isEmpty()) {
                body.append("\n      ").append(shopUrl).append("#product=").append(p.getProductId());
            }
            body.append('\n');
        }
        body.append("\nIn the shop, open My account and tap \"Buy again\" on a past order to refill your cart in one go.\n");
        body.append(preferences.footer(phno));
        return body.toString();
    }
}
