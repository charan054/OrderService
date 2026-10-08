package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.Subscription;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.SubscriptionRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Subscribe and save: a customer picks a product, a quantity and how often (every 7 to 90 days), and each time it
 * falls due an ordinary cash-on-delivery order is placed for them at subscription.discount-percent off. Cash on delivery
 * because an unattended order has no PIN or approval to pay with - and no card or PIN is ever stored for this. The usual
 * order checks all still apply (stock, delivery pincode, cash-on-delivery limits); an order that cannot be placed is
 * retried the next day, and three failures in a row pause the subscription.
 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    static final Set<Integer> INTERVALS = Set.of(7, 14, 30, 60, 90);
    static final int MAX_QUANTITY = 20;
    static final int MAX_ACTIVE_PER_CUSTOMER = 10;
    static final int MAX_FAILURES = 3;

    public record Terms(double discountPercent, List<Integer> intervalDays, int maxQuantity) {
    }

    public record RunResult(int placed, int failed, int paused) {
    }

    private final SubscriptionRepository subscriptions;
    private final OrderService orderService;
    private final ProductClient productClient;
    private final ShippingAddressRepository addresses;
    private final Clock clock;
    private final double discountPercent;

    public SubscriptionService(SubscriptionRepository subscriptions, OrderService orderService, ProductClient productClient,
                               ShippingAddressRepository addresses, Clock clock,
                               @Value("${subscription.discount-percent:5}") double discountPercent) {
        this.subscriptions = subscriptions;
        this.orderService = orderService;
        this.productClient = productClient;
        this.addresses = addresses;
        this.clock = clock;
        this.discountPercent = Math.max(0, Math.min(discountPercent, 50));
    }

    public Terms terms() {
        return new Terms(discountPercent, INTERVALS.stream().sorted().toList(), MAX_QUANTITY);
    }

    public Subscription create(long phno, String customerName, int productId, int quantity, int intervalDays,
                               Long shippingAddressId, Boolean startNow) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new ProductException("Quantity must be between 1 and " + MAX_QUANTITY);
        }
        if (!INTERVALS.contains(intervalDays)) {
            throw new ProductException("Choose a delivery every 7, 14, 30, 60 or 90 days");
        }
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            product = null;
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        if (shippingAddressId != null) {
            ShippingAddress address = addresses.findById(shippingAddressId).orElseThrow(() -> new OrderNotFoundException("Address not found"));
            if (address.getCustomerPhno() != phno) {
                throw new OrderNotFoundException("Address not found");
            }
        }
        if (subscriptions.countByCustomerPhnoAndStatusIn(phno, List.of(Subscription.ACTIVE, Subscription.PAUSED)) >= MAX_ACTIVE_PER_CUSTOMER) {
            throw new ProductException("You can have at most " + MAX_ACTIVE_PER_CUSTOMER + " subscriptions - cancel one first");
        }
        Instant now = clock.instant();
        Subscription s = new Subscription();
        s.setCustomerPhno(phno);
        s.setCustomerName(customerName == null || customerName.isBlank() ? null : customerName.trim().substring(0, Math.min(100, customerName.trim().length())));
        s.setProductId(productId);
        s.setQuantity(quantity);
        s.setIntervalDays(intervalDays);
        s.setShippingAddressId(shippingAddressId);
        s.setDiscountPercent(discountPercent);
        s.setStatus(Subscription.ACTIVE);
        s.setNextRunAt(startNow == null || startNow ? now : now.plus(Duration.ofDays(intervalDays)));
        s.setCreatedAt(now);
        return subscriptions.save(s);
    }

    public List<Subscription> mine(long phno) {
        return subscriptions.findByCustomerPhnoOrderByIdDesc(phno);
    }

    public List<Subscription> all() {
        return subscriptions.findAllByOrderByIdDesc();
    }

    public Subscription pause(long id, long phno) {
        Subscription s = owned(id, phno);
        if (Subscription.ACTIVE.equals(s.getStatus())) {
            s.setStatus(Subscription.PAUSED);
            subscriptions.save(s);
        }
        return s;
    }

    /** Back to ACTIVE; a due date that passed while it was paused moves to now (one catch-up order, not several). */
    public Subscription resume(long id, long phno) {
        Subscription s = owned(id, phno);
        if (Subscription.PAUSED.equals(s.getStatus())) {
            s.setStatus(Subscription.ACTIVE);
            s.setConsecutiveFailures(0);
            s.setLastError(null);
            Instant now = clock.instant();
            if (s.getNextRunAt() == null || s.getNextRunAt().isBefore(now)) {
                s.setNextRunAt(now);
            }
            subscriptions.save(s);
        }
        return s;
    }

    /** Skips the next delivery: the due date moves one interval later. */
    public Subscription skipNext(long id, long phno) {
        Subscription s = owned(id, phno);
        if (!Subscription.CANCELLED.equals(s.getStatus())) {
            Instant base = s.getNextRunAt() != null && s.getNextRunAt().isAfter(clock.instant()) ? s.getNextRunAt() : clock.instant();
            s.setNextRunAt(base.plus(Duration.ofDays(s.getIntervalDays())));
            subscriptions.save(s);
        }
        return s;
    }

    public Subscription cancel(long id, long phno) {
        Subscription s = owned(id, phno);
        if (!Subscription.CANCELLED.equals(s.getStatus())) {
            s.setStatus(Subscription.CANCELLED);
            subscriptions.save(s);
        }
        return s;
    }

    // Someone else's subscription is a 404, same as orders, so ids cannot be probed.
    private Subscription owned(long id, long phno) {
        Subscription s = subscriptions.findById(id).orElseThrow(() -> new OrderNotFoundException("Subscription not found"));
        if (s.getCustomerPhno() != phno) {
            throw new OrderNotFoundException("Subscription not found");
        }
        return s;
    }

    /** Places every order that has fallen due. Safe to call repeatedly: each subscription's next date moves before its order is placed. */
    public RunResult runDue() {
        Instant now = clock.instant();
        int placed = 0, failed = 0, paused = 0;
        for (Subscription s : subscriptions.findByStatusAndNextRunAtLessThanEqualOrderByIdAsc(Subscription.ACTIVE, now)) {
            // Claim it first: if the process dies mid-order, or a second run overlaps, it is not ordered twice.
            s.setNextRunAt(now.plus(Duration.ofDays(s.getIntervalDays())));
            subscriptions.save(s);
            try {
                Cart order = orderService.placeSubscriptionOrder(newOrder(s), s.getId(), s.getDiscountPercent());
                s.setLastOrderId(order.getOrderId());
                s.setOrdersPlaced(s.getOrdersPlaced() + 1);
                s.setConsecutiveFailures(0);
                s.setLastError(null);
                subscriptions.save(s);
                placed++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Subscription {} could not place its order: {}", s.getId(), e.getMessage());
                s.setConsecutiveFailures(s.getConsecutiveFailures() + 1);
                s.setLastError(clip(e.getMessage()));
                if (s.getConsecutiveFailures() >= MAX_FAILURES) {
                    s.setStatus(Subscription.PAUSED);
                    paused++;
                } else {
                    s.setNextRunAt(now.plus(Duration.ofDays(1)));
                }
                subscriptions.save(s);
            }
        }
        return new RunResult(placed, failed, paused);
    }

    private static Cart newOrder(Subscription s) {
        Cart cart = new Cart();
        cart.setCustomerPhno(s.getCustomerPhno());
        cart.setCustomerName(s.getCustomerName());
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setShippingAddressId(s.getShippingAddressId());
        cart.setDeliveryNote("Subscription #" + s.getId());
        OrderItem item = new OrderItem();
        item.setProductId(s.getProductId());
        item.setProductQuantity(s.getQuantity());
        cart.setOrderItems(new java.util.ArrayList<>(List.of(item)));
        return cart;
    }

    private static String clip(String message) {
        String m = message == null || message.isBlank() ? "Order could not be placed" : message.trim();
        return m.length() <= 200 ? m : m.substring(0, 200);
    }
}
