package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.DeliveryAttempt;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.DeliveryAttemptRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where deliveries go wrong: for each pincode, how many orders were shipped in the last N days and how many of those had a
 * failed attempt or were marked delayed, so the shop can see which areas need a different carrier or slot. An order whose
 * address is gone (or was typed free-hand) is counted under "unknown".
 */
@Service
public class DeliveryProblemService {
    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 365;
    static final String UNKNOWN = "unknown";

    public record Area(String pincode, String city, int shipped, int failedOrders, int failedAttempts, int delayedOrders,
                       double problemPercent, Map<String, Integer> reasons) {
    }

    public record Report(int days, int shipped, int problemOrders, List<Area> areas) {
    }

    private final TrackingEventRepository tracking;
    private final DeliveryAttemptRepository attempts;
    private final CartRepository orders;
    private final ShippingAddressRepository addresses;
    private final Clock clock;

    public DeliveryProblemService(TrackingEventRepository tracking, DeliveryAttemptRepository attempts, CartRepository orders,
                                  ShippingAddressRepository addresses, Clock clock) {
        this.tracking = tracking;
        this.attempts = attempts;
        this.orders = orders;
        this.addresses = addresses;
        this.clock = clock;
    }

    public Report report(Integer days) {
        int window = days == null ? DEFAULT_DAYS : days;
        if (window < 1 || window > MAX_DAYS) {
            throw new ProductException("Choose between 1 and " + MAX_DAYS + " days");
        }
        Instant since = clock.instant().minus(Duration.ofDays(window));

        Set<Long> shippedIds = new HashSet<>();
        for (TrackingEvent e : tracking.findByStatusAndTimestampGreaterThanEqual(OrderStatus.SHIPPED, since)) {
            if (e.getOrderId() != null) {
                shippedIds.add(e.getOrderId());
            }
        }
        List<DeliveryAttempt> failed = attempts.findByCreatedAtGreaterThanEqual(since);
        Set<Long> relevant = new HashSet<>(shippedIds);
        failed.forEach(a -> relevant.add(a.getOrderId()));

        Map<Long, Cart> byId = new HashMap<>();
        orders.findAllById(relevant).forEach(c -> byId.put(c.getOrderId(), c));
        Set<Long> addressIds = new HashSet<>();
        byId.values().forEach(c -> {
            if (c.getShippingAddressId() != null) {
                addressIds.add(c.getShippingAddressId());
            }
        });
        Map<Long, ShippingAddress> addressById = new HashMap<>();
        addresses.findAllById(addressIds).forEach(a -> addressById.put(a.getId(), a));

        Map<String, Acc> areas = new TreeMap<>();
        Map<Long, String> pinOf = new HashMap<>();
        for (long id : relevant) {
            Cart cart = byId.get(id);
            if (cart == null) {
                continue;
            }
            ShippingAddress address = cart.getShippingAddressId() == null ? null : addressById.get(cart.getShippingAddressId());
            String pin = address == null || address.getPincode() == null || address.getPincode().isBlank() ? UNKNOWN : address.getPincode().trim();
            pinOf.put(id, pin);
            Acc acc = areas.computeIfAbsent(pin, k -> new Acc());
            if (address != null && address.getCity() != null && acc.city == null) {
                acc.city = address.getCity();
            }
            if (shippedIds.contains(id)) {
                acc.shipped++;
            }
            boolean delayed = cart.getDelayReason() != null && !cart.getDelayReason().isBlank();
            if (delayed) {
                acc.delayedIds.add(id);
            }
        }
        for (DeliveryAttempt a : failed) {
            String pin = pinOf.get(a.getOrderId());
            if (pin == null) {
                continue;
            }
            Acc acc = areas.get(pin);
            acc.failedAttempts++;
            acc.reasons.merge(a.getReason(), 1, Integer::sum);
            acc.failedIds.add(a.getOrderId());
        }

        List<Area> result = new ArrayList<>();
        int shippedTotal = 0;
        int problemTotal = 0;
        for (Map.Entry<String, Acc> e : areas.entrySet()) {
            Acc a = e.getValue();
            Set<Long> problemIds = new HashSet<>(a.failedIds);
            problemIds.addAll(a.delayedIds);
            double percent = a.shipped == 0 ? 0 : Math.round(1000.0 * Math.min(problemIds.size(), a.shipped) / a.shipped) / 10.0;
            result.add(new Area(e.getKey(), a.city, a.shipped, a.failedIds.size(), a.failedAttempts, a.delayedIds.size(), percent, a.reasons));
            shippedTotal += a.shipped;
            problemTotal += problemIds.size();
        }
        result.sort(Comparator.comparingInt((Area a) -> a.failedOrders() + a.delayedOrders()).reversed()
                .thenComparing(Comparator.comparingInt(Area::shipped).reversed()).thenComparing(Area::pincode));
        return new Report(window, shippedTotal, problemTotal, result);
    }

    private static final class Acc {
        String city;
        int shipped;
        int failedAttempts;
        final Set<Long> failedIds = new HashSet<>();
        final Set<Long> delayedIds = new HashSet<>();
        final Map<String, Integer> reasons = new TreeMap<>();
    }
}
