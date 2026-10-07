package com.example.orderservice.service;

import com.example.orderservice.dto.TodaySnapshot;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One call for the admin's morning view: what happened today and what is waiting on someone. An order's "placed"
 * time is its first tracking event (as elsewhere); orders with no tracking at all (very old ones) can't be placed
 * "today", and count as stale only if they are still PLACED with no timestamp to prove otherwise - they don't, to
 * avoid flagging ancient rows every day.
 */
@Service
public class TodaySnapshotService {
    static final int MAX_LISTED = 20;
    private static final Set<OrderStatus> STANDING = Set.of(OrderStatus.PLACED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final CartRepository orders;
    private final TrackingEventRepository tracking;
    private final ProductQuestionRepository questions;
    private final Clock clock;

    public TodaySnapshotService(CartRepository orders, TrackingEventRepository tracking,
                                ProductQuestionRepository questions, Clock clock) {
        this.orders = orders;
        this.tracking = tracking;
        this.questions = questions;
        this.clock = clock;
    }

    public TodaySnapshot snapshot(String zone, int staleHours) {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneOffset.UTC : ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            throw new ProductException("Unknown time zone: " + zone);
        }
        if (staleHours < 1 || staleHours > 720) {
            throw new ProductException("staleHours must be between 1 and 720");
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(zoneId).toLocalDate();
        Instant dayStart = today.atStartOfDay(zoneId).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant();

        Map<Long, Instant> placedAt = new HashMap<>();
        int deliveredToday = 0;
        int cancelledToday = 0;
        for (TrackingEvent e : tracking.findAll()) {
            placedAt.merge(e.getOrderId(), e.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
            boolean today1 = e.getTimestamp() != null && !e.getTimestamp().isBefore(dayStart) && e.getTimestamp().isBefore(dayEnd);
            if (today1 && e.getStatus() == OrderStatus.DELIVERED) deliveredToday++;
            if (today1 && e.getStatus() == OrderStatus.CANCELLED) cancelledToday++;
        }

        int placedToday = 0;
        double revenue = 0;
        int pendingPayments = 0;
        Instant staleBefore = now.minus(Duration.ofHours(staleHours));
        List<Cart> stale = new ArrayList<>();
        List<Long> cashUnpaid = new ArrayList<>();
        for (Cart order : orders.findAll()) {
            OrderStatus status = order.getStatus();   // legacy rows can have none
            if (status == null) {
                continue;
            }
            Instant placed = placedAt.get(order.getOrderId());
            if (placed != null && !placed.isBefore(dayStart) && placed.isBefore(dayEnd) && STANDING.contains(status)) {
                placedToday++;
                revenue += Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            }
            if (status == OrderStatus.PENDING_PAYMENT) {
                pendingPayments++;
            }
            if (status == OrderStatus.PLACED && placed != null && placed.isBefore(staleBefore)) {
                stale.add(order);
            }
            if (status == OrderStatus.DELIVERED && order.getPaymentMethod() == PaymentMethod.CASH && !order.isPaid()) {
                cashUnpaid.add(order.getOrderId());
            }
        }
        List<Long> staleIds = stale.stream()
                .sorted(Comparator.comparing(o -> placedAt.get(o.getOrderId())))
                .map(Cart::getOrderId).limit(MAX_LISTED).toList();
        cashUnpaid.sort(Comparator.naturalOrder());
        List<Long> cashIds = cashUnpaid.size() > MAX_LISTED ? cashUnpaid.subList(0, MAX_LISTED) : cashUnpaid;

        return new TodaySnapshot(today, zoneId.getId(), placedToday, Math.round(revenue * 100) / 100.0,
                deliveredToday, cancelledToday,
                new TodaySnapshot.Attention(staleHours, staleIds, List.copyOf(cashIds), pendingPayments,
                        questions.findByAnswerIsNullOrderByIdAsc().size()));
    }
}
