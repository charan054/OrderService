package com.example.orderservice.service;

import com.example.orderservice.dto.BulkCouponRequest;
import com.example.orderservice.dto.BulkCouponResult;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CouponRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mints a batch of single-use coupon codes (PREFIX-XXXXXXXX) for handing out one per recipient - gift cards, win-back
 * mailings, event giveaways. Each code is an ordinary Coupon limited to one redemption in total and one per customer,
 * so whoever redeems it first uses it up; the checkout path needs no changes.
 *
 * Batch codes are unlisted: the customer-facing coupon suggestions (and so the storefront's auto-apply) never show
 * them, since a code meant for one recipient must not be visible to everyone who knows a phone number.
 */
@Service
public class CouponBatchService {
    static final int MAX_COUNT = 500;
    private static final String DEFAULT_PREFIX = "GIFT";
    private static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{2,12}");
    // No 0/O/1/I/L: codes get read out and retyped.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int SUFFIX_LENGTH = 8;

    private final CouponRepository coupons;
    private final Clock clock;
    private final Random random;

    @Autowired
    public CouponBatchService(CouponRepository coupons, Clock clock) {
        this(coupons, clock, new SecureRandom());
    }

    CouponBatchService(CouponRepository coupons, Clock clock, Random random) {
        this.coupons = coupons;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public BulkCouponResult generate(BulkCouponRequest request) {
        String prefix = request.prefix() == null || request.prefix().isBlank()
                ? DEFAULT_PREFIX : request.prefix().trim().toUpperCase(Locale.ROOT);
        if (!PREFIX.matcher(prefix).matches()) {
            throw new ProductException("Prefix must be 2-12 letters or digits");
        }
        if (request.count() < 1 || request.count() > MAX_COUNT) {
            throw new ProductException("Count must be between 1 and " + MAX_COUNT);
        }
        if (request.discountPercent() <= 0 || request.discountPercent() > 100) {
            throw new ProductException("Discount percent must be between 0 and 100");
        }
        if (request.expiryDate() != null && !request.expiryDate().isAfter(clock.instant())) {
            throw new ProductException("Expiry date must be in the future");
        }

        Set<String> codes = new LinkedHashSet<>();
        int attempts = 0;
        while (codes.size() < request.count()) {
            if (++attempts > request.count() * 20) {
                throw new ProductException("Could not find enough unused codes - try a different prefix");
            }
            String code = prefix + "-" + suffix();
            if (!codes.contains(code) && !coupons.existsById(code)) {
                codes.add(code);
            }
        }

        List<Coupon> batch = new ArrayList<>();
        for (String code : codes) {
            Coupon c = new Coupon();
            c.setCode(code);
            c.setDiscountPercent(request.discountPercent());
            c.setActive(true);
            c.setExpiryDate(request.expiryDate());
            c.setMaxRedemptions(1);
            c.setPerCustomerLimit(1);
            c.setUnlisted(true);
            batch.add(c);
        }
        coupons.saveAll(batch);
        return new BulkCouponResult(prefix, request.discountPercent(), request.expiryDate(), List.copyOf(codes));
    }

    private String suffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
