package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.ItemReturnPickup;
import com.example.orderservice.repository.ItemReturnPickupRepository;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
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

    public static final String RECEIVED = "RECEIVED";
    private static final List<String> OPEN = List.of(AWAITING_PICKUP, PICKED_UP);

    private final CartRepository orders;
    private final ItemReturnPickupRepository itemPickups;
    private final OrderService orderService;
    private final CustomerNotifier notifier;
    private final Clock clock;
    private final boolean enabled;

    public ReturnPickupService(CartRepository orders, ItemReturnPickupRepository itemPickups, OrderService orderService,
                               CustomerNotifier notifier, Clock clock, @Value("${returns.pickup:false}") boolean enabled) {
        this.orders = orders;
        this.itemPickups = itemPickups;
        this.clock = clock;
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
        try (var ignoredLock = orderService.lockOrder(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.DELIVERED) {
                throw new ProductException("Only a delivered order can be returned");
            }
            if (cart.getReturnPickupStatus() != null) {
                throw new ProductException("A pickup is already booked for this return");
            }
            if (!itemPickups.findByOrderIdAndStatusIn(orderId, OPEN).isEmpty()) {
                throw new ProductException("Some items of this order already have a return pickup booked - wait for those first");
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
        try (var ignoredLock = orderService.lockOrder(orderId)) {
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

    /** The customer's per-item return call, with pickup on: books a pickup for some units of one item. */
    public Cart scheduleItem(long orderId, int productId, int quantity, String reason, String refundTo) {
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A return reason is required");
        }
        try (var ignoredLock = orderService.lockOrder(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.DELIVERED) {
                throw new ProductException("Only items of a delivered order can be returned");
            }
            if (cart.getReturnPickupStatus() != null) {
                throw new ProductException("The whole order already has a return pickup booked");
            }
            orderService.requireWithinReturnWindow(orderId);
            int booked = itemPickups.findByOrderIdAndStatusIn(orderId, OPEN).stream()
                    .filter(p -> p.getProductId() == productId).mapToInt(ItemReturnPickup::getQuantity).sum();
            orderService.requireReturnableItem(cart, productId, quantity, booked);
            ItemReturnPickup pickup = new ItemReturnPickup();
            pickup.setOrderId(orderId);
            pickup.setCustomerPhno(cart.getCustomerPhno());
            pickup.setProductId(productId);
            pickup.setQuantity(quantity);
            pickup.setReason(reason.trim().length() > 300 ? reason.trim().substring(0, 300) : reason.trim());
            pickup.setRefundTo(refundTo == null || refundTo.isBlank() ? null : refundTo.trim());
            pickup.setStatus(AWAITING_PICKUP);
            pickup.setCreatedAt(clock.instant());
            pickup.setUpdatedAt(pickup.getCreatedAt());
            itemPickups.save(pickup);
            tell(cart, "Pickup booked for your return from order #" + orderId,
                    "We have your return request for " + quantity + " x product #" + productId + " from order #" + orderId
                            + ". We will collect it and refund you as soon as it reaches us.");
            return cart;
        }
    }

    public ItemReturnPickup markItemPickedUp(long pickupId) {
        ItemReturnPickup p = loadItem(pickupId);
        try (var ignoredLock = orderService.lockOrder(p.getOrderId())) {
            p = loadItem(pickupId);
            if (!AWAITING_PICKUP.equals(p.getStatus())) {
                throw new ProductException("This return is not waiting for pickup");
            }
            p.setStatus(PICKED_UP);
            p.setUpdatedAt(clock.instant());
            ItemReturnPickup saved = itemPickups.save(p);
            tell(load(saved.getOrderId()), "Your return from order #" + saved.getOrderId() + " was picked up",
                    "We have picked up " + saved.getQuantity() + " x product #" + saved.getProductId() + " from your return on order #"
                            + saved.getOrderId() + ". Your refund follows once it reaches our warehouse.");
            return saved;
        }
    }

    /** Goods are back: really return those units and refund them. A failed refund leaves the pickup as it was. */
    public ItemReturnPickup markItemReceived(long pickupId) {
        ItemReturnPickup p = loadItem(pickupId);
        if (!PICKED_UP.equals(p.getStatus())) {
            throw new ProductException("Mark the return as picked up first");
        }
        orderService.returnItemByStaff(p.getOrderId(), p.getProductId(), p.getQuantity(), p.getReason(), p.getRefundTo(), p.getId());
        p.setStatus(RECEIVED);
        p.setUpdatedAt(clock.instant());
        return itemPickups.save(p);
    }

    /** Item pickups still on their way back, oldest first. */
    public List<ItemReturnPickup> openItems() {
        return itemPickups.findByStatusInOrderByIdAsc(OPEN);
    }

    private ItemReturnPickup loadItem(long id) {
        return itemPickups.findById(id).orElseThrow(() -> new OrderNotFoundException("Return pickup not found"));
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
