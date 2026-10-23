package com.example.orderservice.service;

import com.example.orderservice.entity.OrderStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The kinds of browser notification a customer can switch off. An order update belongs to one kind by the order's status:
 * placed (the confirmation), delivery (shipped, delivered and delivery news) and changes (cancellations, returns and the rest).
 */
public enum PushKind {
    PLACED("placed", "Order placed"),
    DELIVERY("delivery", "Shipped and delivered"),
    CHANGES("changes", "Cancellations and returns");

    private final String key;
    private final String label;

    PushKind(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static PushKind of(OrderStatus status) {
        if (status == OrderStatus.PLACED) {
            return PLACED;
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            return DELIVERY;
        }
        return CHANGES;
    }

    public static boolean isMuted(String stored, OrderStatus status) {
        return parse(stored).contains(of(status));
    }

    public static Set<PushKind> parse(String stored) {
        Set<PushKind> result = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) {
            return result;
        }
        for (String part : stored.split(",")) {
            String k = part.trim().toLowerCase(Locale.ROOT);
            for (PushKind kind : values()) {
                if (kind.key.equals(k)) {
                    result.add(kind);
                }
            }
        }
        return result;
    }

    public static String serialize(Set<PushKind> muted) {
        List<String> keys = new ArrayList<>();
        for (PushKind kind : values()) {
            if (muted.contains(kind)) {
                keys.add(kind.key);
            }
        }
        return keys.isEmpty() ? null : String.join(",", keys);
    }
}
