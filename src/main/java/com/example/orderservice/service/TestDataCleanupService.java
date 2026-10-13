package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AccountDeletionResult;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Clears the leftovers of testing - throwaway customers - from the dev database. Off unless testdata.cleanup-enabled=true,
 * and it only ever touches phone numbers that start with testdata.phone-prefix (default 9000, i.e. 9000000000-9000999999).
 * It does exactly what a customer deleting their own account does (AccountErasure): personal data, wishlists, addresses,
 * credit and sessions go, while orders stay as anonymised rows - so invoice numbers, credit notes and the GST report are
 * never left with gaps. A dry run (the default) only lists what would be cleared.
 */
@Service
public class TestDataCleanupService {
    private final CartRepository carts;
    private final CustomerAccountRepository accounts;
    private final ShippingAddressRepository addresses;
    private final StoreCreditAccountRepository credit;
    private final LoyaltyAccountRepository loyalty;
    private final AccountErasure erasure;
    private final ProductClient productClient;
    private final boolean enabled;
    private final String prefix;
    private final String serviceKey;

    public TestDataCleanupService(CartRepository carts, CustomerAccountRepository accounts, ShippingAddressRepository addresses,
                                  StoreCreditAccountRepository credit, LoyaltyAccountRepository loyalty, AccountErasure erasure,
                                  ProductClient productClient,
                                  @Value("${testdata.cleanup-enabled:false}") boolean enabled,
                                  @Value("${testdata.phone-prefix:9000}") String prefix,
                                  @Value("${internal.service.api-key}") String serviceKey) {
        this.carts = carts;
        this.accounts = accounts;
        this.addresses = addresses;
        this.credit = credit;
        this.loyalty = loyalty;
        this.erasure = erasure;
        this.productClient = productClient;
        this.enabled = enabled;
        this.prefix = prefix == null ? "" : prefix.trim();
        this.serviceKey = serviceKey;
    }

    public record Entry(long phno, int orders, boolean hasAccount) {
    }

    public record Preview(String prefix, List<Entry> customers, int orders) {
    }

    public record Outcome(boolean dryRun, int customers, int ordersAnonymised, int addressesErased, int reviewsAnonymised,
                          List<Long> failed) {
    }

    public Preview preview() {
        requireEnabled();
        Map<Long, int[]> counts = new TreeMap<>();   // phno -> {orders, hasAccount}
        for (Cart c : carts.findAll()) {
            if (inRange(c.getCustomerPhno())) {
                counts.computeIfAbsent(c.getCustomerPhno(), k -> new int[2])[0]++;
            }
        }
        Set<Long> others = new TreeSet<>();
        accounts.findAll().forEach(a -> {
            if (inRange(a.getPhno())) {
                counts.computeIfAbsent(a.getPhno(), k -> new int[2])[1] = 1;
            }
        });
        addresses.findAll().forEach(a -> others.add(a.getCustomerPhno()));
        credit.findAll().forEach(a -> others.add(a.getPhno()));
        loyalty.findAll().forEach(a -> others.add(a.getCustomerPhno()));
        others.stream().filter(this::inRange).forEach(p -> counts.computeIfAbsent(p, k -> new int[2]));
        List<Entry> customers = new ArrayList<>();
        int orders = 0;
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            customers.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1] == 1));
            orders += e.getValue()[0];
        }
        return new Preview(prefix, customers, orders);
    }

    /** Erases every test customer found by preview(). With dryRun nothing changes. */
    public Outcome run(boolean dryRun) {
        Preview preview = preview();
        if (dryRun) {
            return new Outcome(true, preview.customers().size(), preview.orders(), 0, 0, List.of());
        }
        int customers = 0;
        int orders = 0;
        int addressCount = 0;
        int reviews = 0;
        List<Long> failed = new ArrayList<>();
        for (Entry e : preview.customers()) {
            try {
                int anonymisedReviews = productClient.anonymiseReviews(serviceKey, e.phno());
                AccountDeletionResult result = erasure.erase(e.phno(), anonymisedReviews);
                customers++;
                orders += result.ordersAnonymised();
                addressCount += result.addressesErased();
                reviews += result.reviewsAnonymised();
            } catch (RuntimeException ex) {
                failed.add(e.phno());   // ProductService unreachable etc.: nothing was erased for this customer, retry later
            }
        }
        return new Outcome(false, customers, orders, addressCount, reviews, failed);
    }

    private boolean inRange(long phno) {
        return !prefix.isEmpty() && String.valueOf(phno).length() == 10 && String.valueOf(phno).startsWith(prefix);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ProductException("Test-data cleanup is switched off (set TESTDATA_CLEANUP_ENABLED=true to allow it)");
        }
        if (prefix.length() < 3) {
            throw new ProductException("testdata.phone-prefix must be at least 3 digits");
        }
    }
}
