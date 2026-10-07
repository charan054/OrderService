package com.example.orderservice.service;

import com.example.orderservice.dto.CodEligibility;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CodOverride;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CodOverrideRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Cash on delivery costs the store a wasted trip every time it is refused at the door, and nothing is charged up
 * front, so it is the easiest payment method to abuse. Two automatic rules, checked at checkout before anything is
 * created or stock is touched:
 * <ul>
 *   <li>a phone number with cod.max-failed-orders or more cash orders that ended CANCELLED or RETURNED in the last
 *   cod.lookback-days loses cash on delivery (it has to pay online);</li>
 *   <li>until a phone number has had one order delivered, cash is only accepted up to cod.new-customer-max-amount
 *   (0 = no cap).</li>
 * </ul>
 * An admin can override either way per number (CodOverride). Orders placed before tracking existed have no date and
 * are outside every look-back window. cod.enabled=false turns the automatic rules off (overrides still apply).
 */
@Service
public class CodRiskService {
    private final CartRepository orders;
    private final TrackingEventRepository trackingEvents;
    private final CodOverrideRepository overrides;
    private final Clock clock;
    private final boolean enabled;
    private final int maxFailedOrders;
    private final Duration lookback;
    private final double newCustomerMaxAmount;

    public CodRiskService(CartRepository orders, TrackingEventRepository trackingEvents, CodOverrideRepository overrides,
                          Clock clock,
                          @Value("${cod.enabled:true}") boolean enabled,
                          @Value("${cod.max-failed-orders:3}") int maxFailedOrders,
                          @Value("${cod.lookback-days:180}") long lookbackDays,
                          @Value("${cod.new-customer-max-amount:2000}") double newCustomerMaxAmount) {
        this.orders = orders;
        this.trackingEvents = trackingEvents;
        this.overrides = overrides;
        this.clock = clock;
        this.enabled = enabled;
        this.maxFailedOrders = maxFailedOrders;
        this.lookback = Duration.ofDays(lookbackDays);
        this.newCustomerMaxAmount = newCustomerMaxAmount;
    }

    public CodEligibility check(long phno) {
        CodOverride override = overrides.findById(phno).orElse(null);
        List<Cart> history = orders.findBycustomerPhno(phno);
        Instant since = clock.instant().minus(lookback);
        int failed = 0;
        boolean delivered = false;
        for (Cart order : history) {
            if (order.getStatus() == OrderStatus.DELIVERED) {
                delivered = true;
            }
            boolean failedCash = order.getPaymentMethod() == PaymentMethod.CASH
                    && (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.RETURNED);
            if (failedCash && placedAfter(order, since)) {
                failed++;
            }
        }
        String mode = override == null ? null : override.getMode().name();
        String note = override == null ? null : override.getNote();
        if (override != null && override.getMode() == CodOverride.Mode.ALLOW) {
            return new CodEligibility(true, null, null, mode, note, failed, delivered);
        }
        if (override != null && override.getMode() == CodOverride.Mode.BLOCK) {
            return new CodEligibility(false, "Cash on delivery isn't available for this account. Please pay online.",
                    null, mode, note, failed, delivered);
        }
        if (!enabled) {
            return new CodEligibility(true, null, null, null, null, failed, delivered);
        }
        if (failed >= maxFailedOrders) {
            return new CodEligibility(false, "Cash on delivery isn't available because several recent cash orders "
                    + "were cancelled or returned. Please pay online.", null, null, null, failed, delivered);
        }
        Double cap = !delivered && newCustomerMaxAmount > 0 ? newCustomerMaxAmount : null;
        return new CodEligibility(true, null, cap, null, null, failed, delivered);
    }

    /** Called by checkout for a CASH order once its final total is known; throws (400) when cash isn't allowed. */
    public void assertCashAllowed(long phno, double orderTotal) {
        CodEligibility eligibility = check(phno);
        if (!eligibility.available()) {
            throw new ProductException(eligibility.reason());
        }
        if (eligibility.maxAmount() != null && orderTotal > eligibility.maxAmount()) {
            throw new ProductException("Cash on delivery is limited to Rs. " + money(eligibility.maxAmount())
                    + " until your first order has been delivered. Please pay online for this order.");
        }
    }

    public CodEligibility setOverride(long phno, String mode, String note) {
        String wanted = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (wanted.equals("AUTO")) {
            overrides.deleteById(phno);
            return check(phno);
        }
        CodOverride.Mode parsed;
        try {
            parsed = CodOverride.Mode.valueOf(wanted);
        } catch (IllegalArgumentException e) {
            throw new ProductException("mode must be AUTO, ALLOW or BLOCK");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > 200) {
            throw new ProductException("note must be at most 200 characters");
        }
        CodOverride override = overrides.findById(phno).orElseGet(CodOverride::new);
        override.setPhno(phno);
        override.setMode(parsed);
        override.setNote(trimmed);
        override.setUpdatedAt(clock.instant());
        overrides.save(override);
        return check(phno);
    }

    private boolean placedAfter(Cart order, Instant since) {
        if (order.getOrderId() == null) {
            return false;
        }
        return trackingEvents.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                .map(TrackingEvent::getTimestamp)
                .filter(t -> t != null)
                .findFirst()
                .map(t -> !t.isBefore(since))
                .orElse(false);
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
