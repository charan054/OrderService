package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.DeliveryAttempt;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.DeliveryAttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Staff record that a delivery attempt failed (customer away, wrong address, refused, ...). The order stays SHIPPED and
 * the customer is told which attempt it was; staff then either try again (and deliver as usual) or, once at least one
 * attempt failed, send the order back to stock - that cancels it and refunds it with the shop's own key. After
 * delivery.max-attempts failures the response says it is time to give up. A cash order sent back counts against the
 * customer's cash-on-delivery record like any cancelled cash order.
 */
@Service
public class DeliveryFailureService {
    static final List<String> REASONS = List.of("CUSTOMER_UNAVAILABLE", "ADDRESS_ISSUE", "REFUSED", "PAYMENT_NOT_READY", "OTHER");
    static final int MAX_NOTE = 200;

    private final CartRepository orders;
    private final DeliveryAttemptRepository attempts;
    private final OrderService orderService;
    private final CustomerNotifier notifier;
    private final Clock clock;
    private final int maxAttempts;

    public DeliveryFailureService(CartRepository orders, DeliveryAttemptRepository attempts, OrderService orderService,
                                  CustomerNotifier notifier, Clock clock, @Value("${delivery.max-attempts:3}") int maxAttempts) {
        this.orders = orders;
        this.attempts = attempts;
        this.orderService = orderService;
        this.notifier = notifier;
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public record Result(DeliveryAttempt attempt, int attemptsSoFar, int maxAttempts, boolean giveUpAdvised) {
    }

    public Result record(long orderId, String reason, String note) {
        String code = reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(code)) {
            throw new ProductException("Choose a reason: " + String.join(", ", REASONS));
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new ProductException("Note must be at most " + MAX_NOTE + " characters");
        }
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.SHIPPED) {
                throw new ProductException("Only a shipped order can have a failed delivery");
            }
            DeliveryAttempt a = new DeliveryAttempt();
            a.setOrderId(orderId);
            a.setAttemptNo((int) attempts.countByOrderId(orderId) + 1);
            a.setReason(code);
            a.setNote(cleanNote);
            a.setCreatedAt(clock.instant());
            DeliveryAttempt saved = attempts.save(a);
            boolean giveUp = saved.getAttemptNo() >= maxAttempts;
            tell(cart, "We could not deliver order #" + orderId,
                    "We tried to deliver your order #" + orderId + " (attempt " + saved.getAttemptNo() + ") but could not hand it over: "
                            + code.toLowerCase(Locale.ROOT).replace('_', ' ') + "." + (cleanNote == null ? "" : " " + cleanNote)
                            + (giveUp ? "\nWe will not try again - your order is going back to us." : "\nWe will try again soon."));
            return new Result(saved, saved.getAttemptNo(), maxAttempts, giveUp);
        }
    }

    /** Gives up on a shipped order that failed to deliver: it is cancelled, put back in stock and refunded. */
    public Cart returnToStock(long orderId, String note) {
        synchronized (orderService.orderLock(orderId)) {
            if (attempts.countByOrderId(orderId) == 0) {
                throw new ProductException("Record a failed delivery attempt first");
            }
            return orderService.cancelUndelivered(orderId, note == null || note.isBlank() ? "Could not be delivered" : note);
        }
    }

    public List<DeliveryAttempt> forOrder(long orderId) {
        return attempts.findByOrderIdOrderByAttemptNoAsc(orderId);
    }

    private Cart load(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    private void tell(Cart cart, String subject, String lead) {
        try {
            notifier.notifyOrderUpdate(cart, subject, lead);
        } catch (RuntimeException e) {
            // the attempt is recorded; the notifier logs its own failure
        }
    }
}
