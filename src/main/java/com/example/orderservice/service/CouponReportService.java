package com.example.orderservice.service;

import com.example.orderservice.dto.CouponReport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CouponRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Per-coupon results from the orders that used each code. In memory over every order, like the other admin analytics. */
@Service
public class CouponReportService {
    private static final Set<OrderStatus> STANDING = Set.of(OrderStatus.PLACED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    private static final Set<OrderStatus> LOST = Set.of(OrderStatus.CANCELLED, OrderStatus.RETURNED);

    private final CartRepository orders;
    private final CouponRepository coupons;
    private final Clock clock;

    public CouponReportService(CartRepository orders, CouponRepository coupons, Clock clock) {
        this.orders = orders;
        this.coupons = coupons;
        this.clock = clock;
    }

    private static final class Tally {
        int orders;
        int standing;
        int lost;
        double discount;
        double net;
    }

    public CouponReport report() {
        Instant now = clock.instant();
        Map<String, Tally> byCode = new HashMap<>();
        for (Cart order : orders.findAll()) {
            String code = order.getCouponCode() == null ? "" : order.getCouponCode().trim().toUpperCase(Locale.ROOT);
            OrderStatus status = order.getStatus();
            // No code, or an order that never became a real one (null = a row from before statuses existed).
            if (code.isEmpty() || status == null || (!STANDING.contains(status) && !LOST.contains(status))) {
                continue;
            }
            Tally t = byCode.computeIfAbsent(code, k -> new Tally());
            t.orders++;
            if (STANDING.contains(status)) {
                t.standing++;
                t.discount += order.getDiscountAmount();
                t.net += Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            } else {
                t.lost++;
            }
        }
        Map<String, Coupon> known = new HashMap<>();
        for (Coupon c : coupons.findAll()) {
            known.put(c.getCode().trim().toUpperCase(Locale.ROOT), c);
            byCode.computeIfAbsent(c.getCode().trim().toUpperCase(Locale.ROOT), k -> new Tally());
        }
        List<CouponReport.Row> rows = byCode.entrySet().stream().map(e -> {
            Tally t = e.getValue();
            Coupon c = known.get(e.getKey());
            return new CouponReport.Row(e.getKey(), c != null, c == null ? null : c.getDiscountPercent(),
                    c != null && c.isActive(), c != null && c.getExpiryDate() != null && c.getExpiryDate().isBefore(now),
                    c != null && c.isUnlisted(), t.orders, t.standing, t.lost, round2(t.discount), round2(t.net),
                    t.standing == 0 ? null : round2(t.net / t.standing), c == null ? t.orders : c.getRedemptionCount(),
                    c == null ? null : c.getMaxRedemptions());
        }).sorted(Comparator.comparingDouble(CouponReport.Row::discountGiven).reversed()
                .thenComparing(CouponReport.Row::code)).toList();
        return new CouponReport(rows, round2(rows.stream().mapToDouble(CouponReport.Row::discountGiven).sum()),
                round2(rows.stream().mapToDouble(CouponReport.Row::netRevenue).sum()));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
