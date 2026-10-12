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
 * Optional "ask first" step before a customer can return a delivered order (returns.require-approval, off by default).
 * The customer requests, staff approve or reject, and an approved customer then completes the return themselves with the
 * normal return call - a PhonePe refund needs their own PIN, which staff do not have. Staff (service key) are never gated.
 */
@Service
public class ReturnApprovalService {
    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    static final int MAX_TEXT = 300;

    private final CartRepository orders;
    private final OrderService orderService;
    private final CustomerNotifier notifier;
    private final boolean required;

    public ReturnApprovalService(CartRepository orders, OrderService orderService, CustomerNotifier notifier,
                                 @Value("${returns.require-approval:false}") boolean required) {
        this.orders = orders;
        this.orderService = orderService;
        this.notifier = notifier;
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    /** The customer asks to return a delivered order. Asking again after a rejection is allowed. */
    public Cart request(long orderId, String reason) {
        if (!required) {
            throw new ProductException("Returns don't need approval - return the order directly");
        }
        String clean = clean(reason, "Reason");
        if (clean == null) {
            throw new ProductException("Please say why you want to return this order");
        }
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.DELIVERED) {
                throw new ProductException("Only a delivered order can be returned");
            }
            if (REQUESTED.equals(cart.getReturnRequestStatus()) || APPROVED.equals(cart.getReturnRequestStatus())) {
                throw new ProductException("A return has already been requested for this order");
            }
            orderService.requireWithinReturnWindow(orderId);
            cart.setReturnRequestStatus(REQUESTED);
            cart.setReturnRequestReason(clean);
            cart.setReturnRequestNote(null);
            return orders.save(cart);
        }
    }

    public Cart approve(long orderId) {
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            requireRequested(cart);
            cart.setReturnRequestStatus(APPROVED);
            cart.setReturnRequestNote(null);
            Cart saved = orders.save(cart);
            tell(saved, "Your return for order #" + orderId + " is approved",
                    "Your return request for order #" + orderId + " was approved. Open \"My account\" in the shop and press "
                            + "\"Complete return\" to finish it and get your refund.");
            return saved;
        }
    }

    public Cart reject(long orderId, String note) {
        String clean = clean(note, "Note");
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            requireRequested(cart);
            cart.setReturnRequestStatus(REJECTED);
            cart.setReturnRequestNote(clean);
            Cart saved = orders.save(cart);
            tell(saved, "Your return request for order #" + orderId + " was not approved",
                    "We could not approve your return request for order #" + orderId + "."
                            + (clean == null ? "" : "\nReason: " + clean + ".") + "\nIf you think this is a mistake, reply to us "
                            + "through Help & support in the shop.");
            return saved;
        }
    }

    /** Requests waiting for a decision, oldest order first. */
    public List<Cart> pending() {
        return orders.findAll().stream()
                .filter(o -> REQUESTED.equals(o.getReturnRequestStatus()))
                .sorted(Comparator.comparing(Cart::getOrderId))
                .toList();
    }

    /** For a customer's whole or per-item return: with approval on, only an approved order may go ahead. */
    public void assertCustomerMayReturn(long orderId) {
        if (!required) {
            return;
        }
        Cart cart = load(orderId);
        if (!APPROVED.equals(cart.getReturnRequestStatus())) {
            throw new ProductException(REQUESTED.equals(cart.getReturnRequestStatus())
                    ? "Your return request is still waiting for approval"
                    : "Please request this return first - it has to be approved before you can complete it");
        }
    }

    private Cart load(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    private static void requireRequested(Cart cart) {
        if (!REQUESTED.equals(cart.getReturnRequestStatus())) {
            throw new ProductException("There is no return request waiting for a decision on this order");
        }
    }

    private static String clean(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.length() > MAX_TEXT) {
            throw new ProductException(field + " is too long (at most " + MAX_TEXT + " characters)");
        }
        return t;
    }

    // A failed email never undoes the decision.
    private void tell(Cart cart, String subject, String lead) {
        try {
            notifier.notifyOrderUpdate(cart, subject, lead);
        } catch (RuntimeException e) {
            // recorded by the notifier's own logging; the decision already stands
        }
    }
}
