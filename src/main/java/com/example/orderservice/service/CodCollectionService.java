package com.example.orderservice.service;

import com.example.orderservice.dto.CodCollectionReport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The cash side of cash-on-delivery: collected per day, still owed, and overdue (delivered but not marked collected). */
@Service
public class CodCollectionService {
    static final int DAYS_SHOWN = 14;
    static final int MAX_OVERDUE_LISTED = 50;
    private static final Set<OrderStatus> OWING = Set.of(OrderStatus.PLACED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final CartRepository orders;
    private final TrackingEventRepository tracking;
    private final Clock clock;
    private final int overdueDays;

    public CodCollectionService(CartRepository orders, TrackingEventRepository tracking, Clock clock,
                                @Value("${cod.overdue-days:2}") int overdueDays) {
        this.orders = orders;
        this.tracking = tracking;
        this.clock = clock;
        this.overdueDays = Math.max(1, overdueDays);
    }

    public int overdueDays() {
        return overdueDays;
    }

    /** Ids of delivered cash orders whose cash has been outstanding past the threshold (for Needs Attention). */
    public List<Long> overdueOrderIds() {
        return overdue().stream().map(CodCollectionReport.Overdue::orderId).toList();
    }

    public CodCollectionReport report(String zone) {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneOffset.UTC : ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            throw new ProductException("Unknown time zone: " + zone);
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(zoneId).toLocalDate();
        LocalDate first = today.minusDays(DAYS_SHOWN - 1L);

        Map<LocalDate, double[]> byDay = new TreeMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(d, new double[2]);
        }
        double outstanding = 0;
        int outstandingOrders = 0;
        for (Cart order : orders.findAll()) {
            if (order.getPaymentMethod() != PaymentMethod.CASH) {
                continue;
            }
            double net = Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            if (order.isPaid() && order.getPaidAt() != null && order.getStatus() != OrderStatus.CANCELLED) {
                double[] slot = byDay.get(order.getPaidAt().atZone(zoneId).toLocalDate());
                if (slot != null) {
                    slot[0] += net;
                    slot[1]++;
                }
            } else if (!order.isPaid() && order.getStatus() != null && OWING.contains(order.getStatus()) && net > 0) {
                outstanding += net;
                outstandingOrders++;
            }
        }
        List<CodCollectionReport.Day> days = new ArrayList<>();
        byDay.forEach((d, v) -> days.add(new CodCollectionReport.Day(d.toString(), round(v[0]), (int) v[1])));
        double collectedToday = days.get(days.size() - 1).collected();
        List<CodCollectionReport.Overdue> overdue = overdue();
        double overdueTotal = overdue.stream().mapToDouble(CodCollectionReport.Overdue::amountDue).sum();
        return new CodCollectionReport(now, zoneId.getId(), overdueDays, collectedToday, days, round(outstanding),
                outstandingOrders, round(overdueTotal), overdue.stream().limit(MAX_OVERDUE_LISTED).toList());
    }

    private List<CodCollectionReport.Overdue> overdue() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(overdueDays));
        List<CodCollectionReport.Overdue> out = new ArrayList<>();
        for (Cart order : orders.findAll()) {
            if (order.getPaymentMethod() != PaymentMethod.CASH || order.isPaid() || order.getStatus() != OrderStatus.DELIVERED) {
                continue;
            }
            double due = Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            if (due <= 0) {
                continue;
            }
            // Latest DELIVERED event; an order with none (delivered before tracking existed) can't be aged, so it is left out.
            Instant deliveredAt = tracking.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                    .filter(e -> e.getStatus() == OrderStatus.DELIVERED)
                    .map(TrackingEvent::getTimestamp)
                    .reduce((a, b) -> b).orElse(null);
            if (deliveredAt != null && !deliveredAt.isAfter(cutoff)) {
                out.add(new CodCollectionReport.Overdue(order.getOrderId(), order.getCustomerPhno(), round(due), deliveredAt,
                        Duration.between(deliveredAt, now).toDays()));
            }
        }
        out.sort(Comparator.comparing(CodCollectionReport.Overdue::deliveredAt));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
