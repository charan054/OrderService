package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.client.StockMovementContext;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.ExchangeRequest;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ExchangeRequestRepository;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Swap some delivered units for another product, with no money moving: the customer asks, staff approve (which sets the
 * replacement aside in stock), and when the old units are back staff mark it received (which restocks them). The
 * replacement must not cost more than what the customer paid per unit; a cheaper one is not refunded the difference.
 * Units under an open or finished exchange can no longer be returned for a refund, and the whole order cannot be
 * returned once it has one - the rest are returned item by item.
 */
@Service
public class ExchangeService {
    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String RECEIVED = "RECEIVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    private static final List<String> OPEN = List.of(REQUESTED, APPROVED);
    private static final List<String> COUNTING = List.of(REQUESTED, APPROVED, RECEIVED);

    private final ExchangeRequestRepository exchanges;
    private final CartRepository orders;
    private final OrderService orderService;
    private final ProductClient productClient;
    private final CustomerNotifier notifier;
    private final Clock clock;
    private final String serviceApiKey;

    public ExchangeService(ExchangeRequestRepository exchanges, CartRepository orders, OrderService orderService,
                           ProductClient productClient, CustomerNotifier notifier, Clock clock,
                           @Value("${internal.service.api-key}") String serviceApiKey) {
        this.exchanges = exchanges;
        this.orders = orders;
        this.orderService = orderService;
        this.productClient = productClient;
        this.notifier = notifier;
        this.clock = clock;
        this.serviceApiKey = serviceApiKey;
    }

    public ExchangeRequest request(long orderId, int productId, int quantity, int replacementProductId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ProductException("Please say why you want to exchange this item");
        }
        synchronized (orderService.orderLock(orderId)) {
            Cart cart = load(orderId);
            if (cart.getStatus() != OrderStatus.DELIVERED) {
                throw new ProductException("Only items of a delivered order can be exchanged");
            }
            if (cart.getReturnPickupStatus() != null) {
                throw new ProductException("This order already has a return pickup booked");
            }
            orderService.requireWithinReturnWindow(orderId);
            if (quantity < 1) {
                throw new ProductException("Quantity must be at least 1");
            }
            OrderItem item = cart.getOrderItems().stream().filter(i -> i.getProductId() == productId).findFirst()
                    .orElseThrow(() -> new ProductException("That product is not in this order"));
            int held = exchangedQuantity(orderId, productId);
            if (quantity + held > item.getOutstandingQuantity()) {
                throw new ProductException("Only " + Math.max(0, item.getOutstandingQuantity() - held)
                        + " of that item can still be exchanged");
            }
            if (item.getUnitPrice() == null) {
                throw new ProductException("This order is too old to exchange items - ask us to return it instead");
            }
            Product replacement = replacement(replacementProductId);
            if (replacement.getProductPrice() > item.getUnitPrice() + 0.005) {
                throw new ProductException("The replacement costs more than you paid for this item - pick one at the same "
                        + "price or lower");
            }
            if (replacement.getProductStock() < quantity) {
                throw new ProductException("The replacement is not in stock in that quantity");
            }
            ExchangeRequest r = new ExchangeRequest();
            r.setOrderId(orderId);
            r.setCustomerPhno(cart.getCustomerPhno());
            r.setProductId(productId);
            r.setQuantity(quantity);
            r.setReplacementProductId(replacementProductId);
            r.setReason(reason.trim().length() > 300 ? reason.trim().substring(0, 300) : reason.trim());
            r.setStatus(REQUESTED);
            r.setCreatedAt(clock.instant());
            r.setUpdatedAt(r.getCreatedAt());
            ExchangeRequest saved = exchanges.save(r);
            tell(cart, "Exchange requested for order #" + orderId, "We have your request to exchange " + quantity
                    + " x product #" + productId + " for product #" + replacementProductId + ". We will confirm it shortly.");
            return saved;
        }
    }

    /** Staff accept: the replacement units are set aside in stock right away. */
    public ExchangeRequest approve(long id) {
        ExchangeRequest r = find(id);
        synchronized (orderService.orderLock(r.getOrderId())) {
            r = find(id);
            requireStatus(r, REQUESTED, "Only a new exchange request can be approved");
            Product replacement = replacement(r.getReplacementProductId());
            if (replacement.getProductStock() < r.getQuantity()) {
                throw new ProductException("The replacement is no longer in stock in that quantity");
            }
            try (StockMovementContext.Scope ignored = StockMovementContext.open(StockMovementContext.SALE,
                    "order " + r.getOrderId(), "Exchange replacement")) {
                productClient.updateProductStock(serviceApiKey, r.getReplacementProductId(), -r.getQuantity());
            }
            r.setStatus(APPROVED);
            r.setUpdatedAt(clock.instant());
            ExchangeRequest saved = exchanges.save(r);
            tell(load(saved.getOrderId()), "Your exchange for order #" + saved.getOrderId() + " is approved",
                    "Your exchange is approved. We are sending the replacement and will collect the original item.");
            return saved;
        }
    }

    public ExchangeRequest reject(long id, String note) {
        ExchangeRequest r = find(id);
        synchronized (orderService.orderLock(r.getOrderId())) {
            r = find(id);
            requireStatus(r, REQUESTED, "Only a new exchange request can be rejected");
            r.setStatus(REJECTED);
            r.setUpdatedAt(clock.instant());
            ExchangeRequest saved = exchanges.save(r);
            tell(load(saved.getOrderId()), "Your exchange for order #" + saved.getOrderId() + " was not approved",
                    "We could not approve your exchange." + (note == null || note.isBlank() ? "" : " " + note.trim()));
            return saved;
        }
    }

    /** The customer withdraws a request nobody has approved yet. */
    public ExchangeRequest cancel(long id) {
        ExchangeRequest r = find(id);
        synchronized (orderService.orderLock(r.getOrderId())) {
            r = find(id);
            requireStatus(r, REQUESTED, "Only a request that is still waiting can be withdrawn");
            r.setStatus(CANCELLED);
            r.setUpdatedAt(clock.instant());
            return exchanges.save(r);
        }
    }

    /** The old units are back: they go back into stock and the exchange is complete. */
    public ExchangeRequest markReceived(long id) {
        ExchangeRequest r = find(id);
        synchronized (orderService.orderLock(r.getOrderId())) {
            r = find(id);
            requireStatus(r, APPROVED, "Approve the exchange first");
            try (StockMovementContext.Scope ignored = StockMovementContext.open(StockMovementContext.RETURN,
                    "order " + r.getOrderId(), "Exchange returned")) {
                productClient.updateProductStock(serviceApiKey, r.getProductId(), r.getQuantity());
            }
            r.setStatus(RECEIVED);
            r.setUpdatedAt(clock.instant());
            ExchangeRequest saved = exchanges.save(r);
            tell(load(saved.getOrderId()), "Your exchange for order #" + saved.getOrderId() + " is complete",
                    "We have your original item back - your exchange is complete.");
            return saved;
        }
    }

    public List<ExchangeRequest> forOrder(long orderId) {
        return exchanges.findByOrderIdOrderByIdAsc(orderId);
    }

    public List<ExchangeRequest> open() {
        return exchanges.findByStatusInOrderByIdAsc(OPEN);
    }

    public ExchangeRequest find(long id) {
        return exchanges.findById(id).orElseThrow(() -> new OrderNotFoundException("Exchange not found"));
    }

    /** Units of this product under a waiting, approved or finished exchange (they cannot also be returned for a refund). */
    public int exchangedQuantity(long orderId, int productId) {
        return exchanges.findByOrderIdAndStatusIn(orderId, COUNTING).stream()
                .filter(e -> e.getProductId() == productId).mapToInt(ExchangeRequest::getQuantity).sum();
    }

    public boolean hasExchange(long orderId) {
        return !exchanges.findByOrderIdAndStatusIn(orderId, COUNTING).isEmpty();
    }

    private Product replacement(int productId) {
        try {
            Product p = productClient.getProductById(productId);
            if (p == null) {
                throw new ProductException("The replacement product was not found");
            }
            return p;
        } catch (FeignException e) {
            throw new ProductException("The replacement product was not found");
        }
    }

    private void requireStatus(ExchangeRequest r, String expected, String message) {
        if (!expected.equals(r.getStatus())) {
            throw new ProductException(message);
        }
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
