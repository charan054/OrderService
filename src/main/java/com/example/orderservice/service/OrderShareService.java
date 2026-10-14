package com.example.orderservice.service;

import com.example.orderservice.dto.SharedOrderStatus;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderShareLink;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderShareLinkRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Lets a customer hand out a read-only link to one order's status, e.g. to the person who is waiting for a parcel.
 * Asking again returns the link that is already active; revoking ends every link of the order. A link also stops working
 * after order.share.valid-days (default 30).
 */
@Service
public class OrderShareService {
    private static final int TOKEN_BYTES = 16;

    private final OrderShareLinkRepository links;
    private final CartRepository orders;
    private final TrackingEventRepository tracking;
    private final Clock clock;
    private final Duration validFor;
    private final SecureRandom random = new SecureRandom();

    public OrderShareService(OrderShareLinkRepository links, CartRepository orders, TrackingEventRepository tracking, Clock clock,
                             @Value("${order.share.valid-days:30}") int validDays) {
        this.links = links;
        this.orders = orders;
        this.tracking = tracking;
        this.clock = clock;
        this.validFor = Duration.ofDays(Math.max(1, validDays));
    }

    // A wrong phone number is reported as "not found", like every other order lookup, so ids cannot be probed.
    private Cart ownedOrder(long orderId, long phno) {
        return orders.findById(orderId).filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    @Transactional
    public OrderShareLink getOrCreate(long orderId, long phno) {
        ownedOrder(orderId, phno);
        Instant now = clock.instant();
        return links.findByOrderIdAndRevokedAtIsNull(orderId).stream()
                .filter(l -> l.getExpiresAt() != null && l.getExpiresAt().isAfter(now))
                .findFirst()
                .orElseGet(() -> {
                    byte[] bytes = new byte[TOKEN_BYTES];
                    random.nextBytes(bytes);
                    OrderShareLink link = new OrderShareLink();
                    link.setToken(HexFormat.of().formatHex(bytes));
                    link.setOrderId(orderId);
                    link.setCustomerPhno(phno);
                    link.setCreatedAt(now);
                    link.setExpiresAt(now.plus(validFor));
                    return links.save(link);
                });
    }

    @Transactional
    public int revoke(long orderId, long phno) {
        ownedOrder(orderId, phno);
        Instant now = clock.instant();
        List<OrderShareLink> active = links.findByOrderIdAndRevokedAtIsNull(orderId);
        active.forEach(l -> l.setRevokedAt(now));
        links.saveAll(active);
        return active.size();
    }

    public SharedOrderStatus view(String token) {
        OrderShareLink link = token == null ? null : links.findById(token).orElse(null);
        Instant now = clock.instant();
        if (link == null || link.getRevokedAt() != null || link.getExpiresAt() == null || !link.getExpiresAt().isAfter(now)) {
            throw new OrderNotFoundException("This link is not valid any more");
        }
        Cart order = orders.findById(link.getOrderId()).orElseThrow(() -> new OrderNotFoundException("This link is not valid any more"));
        List<SharedOrderStatus.Step> steps = tracking.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                .map((TrackingEvent e) -> new SharedOrderStatus.Step(String.valueOf(e.getStatus()), e.getTimestamp()))
                .toList();
        return new SharedOrderStatus(order.getOrderId(), String.valueOf(order.getStatus()), steps, order.getCarrier(),
                order.getTrackingNumber(), order.getExpectedDeliveryDate() == null ? null : order.getExpectedDeliveryDate().toString(),
                link.getExpiresAt());
    }
}
