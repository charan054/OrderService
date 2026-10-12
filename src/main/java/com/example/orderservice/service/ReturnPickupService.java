package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Optional "refund only once the goods are back" step (returns.pickup, off by default). The customer's return call then
 * only books a pickup; staff mark it picked up and then received, and only on "received" is the order returned and
 * refunded - with the shop's own key, so a PhonePe refund needs no PIN. Whole-order returns only.
 */
@Service
public class ReturnPickupService {
    public static final String AWAITING_PICKUP = "AWAITING_PICKUP";
    public static final String PICKED_UP = "PICKED_UP";

    private final CartRepository orders;
    private final OrderService orderService;
    private final CustomerNotifier notifier;
    private final boolean enabled;

    public ReturnPickupService(CartRepository orders, OrderService orderService, CustomerNotifier notifier,
                               @Value("${returns.pickup:false}") boolean enabled) {
        this.orders = orders;
        this.orderService = orderService;
        this.notifier = notifier;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The customer's return call, with pickup on: books the pickup instead of refunding. */
    public Cart schedule(long orderId, String reason, String refundTo) {
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A return reason is required");
        }
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.DELIVERED) {
                throw new ProductException("Only a delivered order can be returned");
            }
            if (cart.getReturnPickupStatus() != null) {
                throw new ProductException("A pickup is already booked for this return");
            }
            orderService.requireWithinReturnWindow(orderId);
            cart.setReturnPickupStatus(AWAITING_PICKUP);
            cart.setReturnReason(reason.trim());
            cart.setReturnRefundTo(refundTo == null || refundTo.isBlank() ? null : refundTo.trim());
            Cart saved = orders.save(cart);
            tell(saved, "Pickup booked for your return of order #" + orderId,
                    "We have your return request for order #" + orderId + ". We will collect the items and refund you as soon "
                            + "as they reach us.");
            return saved;
        }
    }

    public Cart markPickedUp(long orderId) {
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            if (!AWAITING_PICKUP.equals(cart.getReturnPickupStatus())) {
                throw new ProductException("This return is not waiting for pickup");
            }
            cart.setReturnPickupStatus(PICKED_UP);
            Cart saved = orders.save(cart);
            tell(saved, "Your return for order #" + orderId + " was picked up",
                    "We have picked up the items from your return of order #" + orderId + ". Your refund follows once they "
                            + "reach our warehouse.");
            return saved;
        }
    }

    /** Goods are back: really return the order and refund it. A failed refund leaves the pickup state as it was. */
    public Cart markReceived(long orderId) {
        Cart cart = load(orderId);
        if (!PICKED_UP.equals(cart.getReturnPickupStatus())) {
            throw new ProductException("Mark the return as picked up first");
        }
        return orderService.returnOrderByStaff(orderId, cart.getReturnReason(), cart.getReturnRefundTo());
    }

    /** Returns that are booked or on their way back, oldest order first. */
    public List<Cart> open() {
        return orders.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.DELIVERED && o.getReturnPickupStatus() != null)
                .sorted(Comparator.comparing(Cart::getOrderId))
                .toList();
    }

    private Cart load(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    private void tell(Cart cart, String subject, String lead) {
        try {
            notifier.notifyOrderUpdate(cart, subject, lead);
        } catch (RuntimeException e) {
            // the step already happened; the notifier logs its own failure
        }
    }
}
