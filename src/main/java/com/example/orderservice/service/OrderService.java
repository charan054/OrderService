package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AdminOrderRow;
import com.example.orderservice.dto.CouponSuggestion;
import com.example.orderservice.dto.CreateUpiCollectRequest;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PendingPaymentSweepResult;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.PhonepeForgotPinRequest;
import com.example.orderservice.dto.PhonepeLoginRequest;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.dto.PhonepeResetPinRequest;
import com.example.orderservice.dto.GuestOrderSummary;
import com.example.orderservice.dto.RevenueTimeseries;
import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.ModerationReview;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.dto.SalesAnalytics;
import com.example.orderservice.dto.StorefrontReview;
import com.example.orderservice.dto.TopSellingProduct;
import com.example.orderservice.dto.UpiCollectRequestResponse;
import com.example.orderservice.dto.WaitlistStatus;
import com.example.orderservice.dto.WishlistPriceAlert;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.entity.CouponRedemption;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.LoyaltyTier;
import com.example.orderservice.entity.LoyaltyTransaction;
import com.example.orderservice.entity.LoyaltyTransactionType;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.dto.BulkTransitionResult;
import com.example.orderservice.dto.CancellationReport;
import com.example.orderservice.dto.OrderHistoryPage;
import com.example.orderservice.entity.ServiceablePincode;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.dto.PincodeServiceability;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CouponRedemptionRepository;
import com.example.orderservice.repository.CouponRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.LoyaltyTransactionRepository;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.ServiceablePincodeRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import com.example.orderservice.repository.WishlistRepository;
import feign.FeignException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private CartRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponRedemptionRepository couponRedemptionRepository;
    @Autowired
    private LoyaltyAccountRepository loyaltyAccountRepository;
    @Autowired
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Autowired
    private WishlistRepository wishlistRepository;
    @Autowired
    private StockWaitlistRepository stockWaitlistRepository;
    @Autowired
    private TrackingEventRepository trackingEventRepository;
    @Autowired
    private ShippingAddressRepository shippingAddressRepository;
    @Autowired
    private ServiceablePincodeRepository serviceablePincodeRepository;
    @Autowired
    private NotificationLogRepository notificationLogRepository;
    @Autowired
    private CustomerNotifier customerNotifier;
    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired
    ProductClient productClient;
    @Autowired
    PhonepeClient phonepeClient;
    @Autowired
    private OrderKafkaProducer orderKafkaProducer;
    @Value("${internal.service.api-key}")
    private String serviceApiKey;
    public Cart order(Cart cart, String authorization, String idempotencyKey) {
        return order(cart, authorization, idempotencyKey, null, null, null);
    }

    // payerPhno/payerPin are only used for the CASH-free "storefront" checkout path, where the caller has no
    // PhonepayService session token yet - never persisted anywhere, only used in-memory to obtain one via
    // phonepeClient.login() below. A caller that already has a token (the admin dashboard, existing integrations)
    // keeps passing it directly through authorization, unchanged.
    public Cart order(Cart cart, String authorization, String idempotencyKey, Long payerPhno, String payerPin) {
        return order(cart, authorization, idempotencyKey, payerPhno, payerPin, null);
    }

    // payerUpiId is a THIRD, mutually exclusive way to pay a PHONEPE order (alongside an already-supplied token
    // and payerPhno/payerPin) - the storefront's newer checkout flow, where the buyer never types a PIN into
    // OrderService at all. Instead of charging synchronously, this creates the order already reserved
    // (PENDING_PAYMENT) and asks PhonepayService to collect the payment from that UPI ID; the buyer approves or
    // declines it themselves, later, directly in PhonepayService - see createPendingUpiOrder()/
    // checkPendingPayment(). Takes priority over authorization/payerPhno+payerPin when present, since it's the
    // more specific signal of which flow the caller wants.
    public Cart order(Cart cart, String authorization, String idempotencyKey, Long payerPhno, String payerPin, String payerUpiId)
    {
        validatePhno(cart.getCustomerPhno());
        sanitizeNewOrder(cart);
        normalizeDeliveryNote(cart);
        normalizeDeliverySlot(cart);
        if (cart.getPaymentMethod() == null) {
            cart.setPaymentMethod(PaymentMethod.PHONEPE);
        }
        // Validate every item and compute the price WITHOUT touching stock yet, so a later item failing
        // (not found, insufficient stock) can never leave an earlier item's stock decremented with no order to show for it.
        double price=0;
        for(OrderItem orderItem : cart.getOrderItems())
        {
            Product pro=productClient.getProductById(orderItem.getProductId());
            if(pro==null)
            {
                throw new ProductException("Product not found");
            }
            if(orderItem.getProductQuantity()>pro.getProductStock())
            {
                throw new ProductException("Product quantity exceeded");
            }
            price=price+(orderItem.getProductQuantity()*pro.getProductPrice());
            orderItem.setUnitPrice(pro.getProductPrice());
            orderItem.setCancelledQuantity(0);
            orderItem.setReturnedQuantity(0);
        }
        // Resolved (and normalized onto the cart) BEFORE charging, same reasoning as stock: an invalid/inactive
        // code must fail before anything - including a payment - has happened.
        double discount = resolveDiscount(cart, price);
        // Same fail-fast reasoning, applied on top of the coupon discount: redeeming more points than the
        // customer's balance actually holds, or more than what's left to pay, must fail before any payment.
        double pointsDiscount = resolvePointsRedemption(cart, price - discount);
        double finalPrice = price - discount - pointsDiscount;
        // Same fail-fast reasoning as stock/coupon above: an address that doesn't exist, or belongs to someone
        // else's phone number, must reject the order before any payment is attempted.
        validateShippingAddress(cart);

        if (cart.getPaymentMethod() == PaymentMethod.PHONEPE && payerUpiId != null && !payerUpiId.isBlank()) {
            return createPendingUpiOrder(cart, finalPrice, discount, payerUpiId);
        }

        // Cash on delivery never touches PhonepayService at all - nothing is charged now, so there is nothing to
        // refund later either (see cancel()/returnOrder()).
        PaymentResponse payment = null;
        if (cart.getPaymentMethod() != PaymentMethod.CASH) {
            // Charge the buyer BEFORE creating the order or touching stock: if PhonepayService refuses the
            // payment (insufficient funds, expired session, locked account, bank down, ...) nothing here should
            // exist either.
            String token = resolveBuyerToken(authorization, payerPhno, payerPin);
            payment = charge(token, finalPrice, idempotencyKey);
        }
        recordCouponRedemption(cart.getCouponCode(), cart.getCustomerPhno());
        cart.setTotalPrice(finalPrice);
        cart.setDiscountAmount(discount);
        cart.setStatus(OrderStatus.PLACED);
        cart.setPaymentTransactionId(payment != null ? payment.transactionId() : null);
        // A PHONEPE order is paid the instant its charge above succeeds; a CASH order isn't paid yet - see
        // markPaid().
        cart.setPaid(payment != null);
        Cart saved=orderRepository.save(cart);
        for(OrderItem orderItem : saved.getOrderItems())
        {
            orderItem.setOrderId(saved.getOrderId());
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(),-orderItem.getProductQuantity());
        }
        Cart result = orderRepository.save(saved);
        recordTracking(result.getOrderId(), OrderStatus.PLACED);
        redeemLoyaltyPoints(result.getCustomerPhno(), result.getPointsRedeemed(), result.getOrderId());
        sendNotification("Order placed successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Items: " + result.getOrderItems().size()
                + " Total: " + result.getTotalPrice());
        notifyCustomer(result, OrderStatus.PLACED, null);
        return result;
    }

    // The request body is deserialized straight into the entity, so a caller could otherwise (a) send an orderId
    // or item ids that make save() overwrite SOMEONE ELSE'S existing order instead of creating one, (b) send
    // zero/negative quantities (negative price, and a "negative" stock decrement that ADDS stock), or pre-set
    // system-managed fields like refundedAmount. Only what the buyer legitimately chooses is kept.
    private void sanitizeNewOrder(Cart cart) {
        if (cart.getOrderItems() == null || cart.getOrderItems().isEmpty()) {
            throw new ProductException("An order needs at least one item");
        }
        Map<Integer, OrderItem> merged = new java.util.LinkedHashMap<>();
        for (OrderItem item : cart.getOrderItems()) {
            if (item == null || item.getProductQuantity() < 1) {
                throw new ProductException("Quantity must be at least 1");
            }
            // Two lines for one product would each pass the stock check alone but together could exceed it.
            OrderItem existing = merged.get(item.getProductId());
            if (existing != null) {
                existing.setProductQuantity(existing.getProductQuantity() + item.getProductQuantity());
            } else {
                item.setId(null);
                item.setOrderId(null);
                item.setReturnReason(null);
                merged.put(item.getProductId(), item);
            }
        }
        cart.setOrderItems(new ArrayList<>(merged.values()));
        cart.setOrderId(null);
        cart.setRefundedAmount(0);
        cart.setReturnReason(null);
        cart.setPaymentTransactionId(null);
        cart.setUpiId(null);
        cart.setPaymentDeadline(null);
        cart.setDiscountAmount(0);
    }

    private static final long UPI_COLLECT_TIMEOUT_MINUTES = 4;

    private String upiMerchantReference(long orderId) {
        return "OrderService-" + orderId;
    }

    // Stock is reserved immediately, same as a normal order - the whole point of the payment window is that this
    // stock is held while the buyer goes to approve it in PhonepayService, not left available for someone else
    // to buy out from under them in the meantime. Coupon redemption and loyalty-point spending are deliberately
    // NOT recorded here (unlike the synchronous path above) - those are real, hard-to-reverse side effects that
    // must wait until the payment has actually gone through, in finalizePaidOrder() below.
    private Cart createPendingUpiOrder(Cart cart, double finalPrice, double discount, String payerUpiId) {
        cart.setTotalPrice(finalPrice);
        cart.setDiscountAmount(discount);
        cart.setStatus(OrderStatus.PENDING_PAYMENT);
        cart.setPaid(false);
        cart.setUpiId(payerUpiId);
        cart.setPaymentDeadline(Instant.now().plus(UPI_COLLECT_TIMEOUT_MINUTES, ChronoUnit.MINUTES));
        Cart saved = orderRepository.save(cart);
        for (OrderItem orderItem : saved.getOrderItems()) {
            orderItem.setOrderId(saved.getOrderId());
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), -orderItem.getProductQuantity());
        }
        Cart result = orderRepository.save(saved);

        try {
            phonepeClient.createUpiCollectRequest(serviceApiKey, new CreateUpiCollectRequest(
                    upiMerchantReference(result.getOrderId()), payerUpiId,
                    BigDecimal.valueOf(finalPrice).setScale(2, RoundingMode.HALF_UP), "Order payment"));
        } catch (FeignException e) {
            // Nothing should exist if the collect request itself couldn't even be created (a malformed UPI ID,
            // PhonepayService unreachable) - restore the stock just reserved and remove the order, the same
            // fail-safe shape a declined charge() already gives the synchronous path.
            for (OrderItem orderItem : result.getOrderItems()) {
                productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
            }
            orderRepository.delete(result);
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        recordTracking(result.getOrderId(), OrderStatus.PENDING_PAYMENT);
        sendNotification("Order awaiting UPI payment approval. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Total: " + result.getTotalPrice());
        return result;
    }

    // Resolves a PENDING_PAYMENT order: the storefront polls this while the buyer goes to approve in
    // PhonepayService, and PendingPaymentSweeper calls it for every pending order on a timer so a buyer who never
    // comes back can't leave their reserved stock stuck. OrderService's OWN deadline is checked FIRST and is
    // authoritative regardless of what PhonepayService's own collect-request expiry says (the two are set to
    // the same duration, but this keeps OrderService in control of how long an order actually holds its
    // reserved stock even if that ever changes independently on PhonepayService's side).
    //
    // A poll and a sweep can hit the same order at the same moment; both would then cancel it and put its stock
    // back twice. Striped locks make them take turns, and the order is re-read inside the lock so the loser sees
    // the winner's result and returns it.
    private static final int PENDING_LOCK_STRIPES = 64;
    private final Object[] pendingLocks = newLocks(PENDING_LOCK_STRIPES);

    private static Object[] newLocks(int count) {
        Object[] locks = new Object[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    public Cart checkPendingPayment(long orderId) {
        synchronized (pendingLocks[(int) Math.floorMod(orderId, (long) PENDING_LOCK_STRIPES)]) {
            return resolvePendingPayment(orderId);
        }
    }

    // Every PENDING_PAYMENT order is checked (not only expired ones): an approved collect request is turned into
    // a placed order promptly even if the buyer closed the tab. One order failing never stops the rest.
    public PendingPaymentSweepResult sweepPendingPayments() {
        int checked = 0;
        int resolved = 0;
        for (Cart pending : orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT)) {
            checked++;
            try {
                if (checkPendingPayment(pending.getOrderId()).getStatus() != OrderStatus.PENDING_PAYMENT) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.error("Pending payment sweep: order {} failed: {}", pending.getOrderId(), e.getMessage());
            }
        }
        if (checked > 0) {
            log.info("Pending payment sweep: {} order(s) checked, {} resolved", checked, resolved);
        }
        return new PendingPaymentSweepResult(checked, resolved);
    }

    private Cart resolvePendingPayment(long orderId) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return cart;
        }
        // A pending order with no deadline is corrupt - treat it as expired rather than holding its stock forever.
        if (cart.getPaymentDeadline() == null || Instant.now().isAfter(cart.getPaymentDeadline())) {
            return cancelUnpaidOrder(cart, "payment window expired");
        }

        UpiCollectRequestResponse request;
        try {
            request = phonepeClient.getUpiCollectRequest(serviceApiKey, upiMerchantReference(orderId));
        } catch (FeignException e) {
            // PhonepayService being briefly unreachable shouldn't cancel a still-valid, still-within-window
            // order - the next poll tries again. Only an explicit DECLINED/EXPIRED answer, or OrderService's
            // own deadline above, ends it early.
            return cart;
        }

        return switch (request.status()) {
            case "APPROVED" -> finalizePaidOrder(cart, request.resultTransactionId());
            case "DECLINED" -> cancelUnpaidOrder(cart, "payment declined");
            case "EXPIRED" -> cancelUnpaidOrder(cart, "payment window expired");
            default -> cart;   // still PENDING on PhonepayService's side - nothing to do yet
        };
    }

    private Cart finalizePaidOrder(Cart cart, Long paymentTransactionId) {
        recordCouponRedemption(cart.getCouponCode(), cart.getCustomerPhno());
        cart.setStatus(OrderStatus.PLACED);
        cart.setPaid(true);
        cart.setPaymentTransactionId(paymentTransactionId);
        cart.setPaymentDeadline(null);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.PLACED);
        redeemLoyaltyPoints(result.getCustomerPhno(), result.getPointsRedeemed(), result.getOrderId());
        sendNotification("Order placed successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Items: " + result.getOrderItems().size()
                + " Total: " + result.getTotalPrice());
        notifyCustomer(result, OrderStatus.PLACED, null);
        return result;
    }

    // No refund call here (unlike cancel()) - a PENDING_PAYMENT order was never actually charged, so there is
    // nothing PhonepayService needs to reverse.
    private Cart cancelUnpaidOrder(Cart cart, String reason) {
        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
            // Same bookkeeping as cancel(): an item that was never going to be delivered shows as cancelled.
            orderItem.setCancelledQuantity(orderItem.getProductQuantity() - orderItem.getReturnedQuantity());
        }
        cart.setStatus(OrderStatus.CANCELLED);
        cart.setPaymentDeadline(null);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.CANCELLED);
        sendNotification("Order cancelled successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Reason: " + reason);
        notifyCustomer(result, OrderStatus.CANCELLED, reason);
        return result;
    }

    // Cancels an order that hasn't already been cancelled: refunds the buyer's own payment in full, and only on
    // a successful refund restores stock and marks the order CANCELLED - a declined/failed refund leaves the
    // order exactly as it was, the same fail-safe shape order() already uses for placing one.
    public Cart cancel(long orderId, String authorization, String idempotencyKey) {
        return cancel(orderId, authorization, idempotencyKey, null, null);
    }

    // payerPhno/payerPin mirror order()'s storefront path - the customer-facing cancel button has no stored
    // session token either, only a phone+PIN entered fresh for this one call (see resolveBuyerToken).
    public Cart cancel(long orderId, String authorization, String idempotencyKey, Long payerPhno, String payerPin) {
        return cancel(orderId, authorization, idempotencyKey, payerPhno, payerPin, null, null);
    }

    static final java.util.List<String> CANCEL_REASONS = java.util.List.of(
            "CHANGED_MIND", "ORDERED_BY_MISTAKE", "FOUND_CHEAPER_ELSEWHERE", "DELIVERY_TOO_SLOW", "WRONG_ADDRESS", "OTHER");
    static final int MAX_CANCEL_NOTE_LENGTH = 200;

    // reason (optional) must be one of CANCEL_REASONS; note (optional) is free text. Both are checked BEFORE anything
    // is refunded, same fail-fast reasoning as everywhere else in this service.
    public Cart cancel(long orderId, String authorization, String idempotencyKey, Long payerPhno, String payerPin,
                       String reason, String note) {
        String reasonCode = reason == null || reason.isBlank() ? null : reason.trim().toUpperCase(java.util.Locale.ROOT);
        if (reasonCode != null && !CANCEL_REASONS.contains(reasonCode)) {
            throw new ProductException("Unknown cancellation reason. Choose one of: " + String.join(", ", CANCEL_REASONS));
        }
        String cancelNote = note == null || note.isBlank() ? null : note.trim();
        if (cancelNote != null && cancelNote.length() > MAX_CANCEL_NOTE_LENGTH) {
            throw new ProductException("Cancellation note must be at most " + MAX_CANCEL_NOTE_LENGTH + " characters");
        }
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() == OrderStatus.CANCELLED) {
            throw new ProductException("This order is already cancelled");
        }
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Only a placed order can be cancelled");
        }
        // A CASH order was never charged through PhonepayService, so it has no paymentTransactionId to refund -
        // that's expected, not the "this order cannot be cancelled" guard below (which instead catches a PHONEPE
        // order that's somehow missing its transaction id, a real data-integrity problem).
        if (cart.getPaymentMethod() != PaymentMethod.CASH) {
            if (cart.getPaymentTransactionId() == null) {
                throw new ProductException("This order cannot be cancelled");
            }
            String token = resolveBuyerToken(authorization, payerPhno, payerPin);
            // No amount: PhonepayService refunds whatever is still unrefunded, i.e. everything minus any
            // per-item cancellations already paid back.
            refund(token, cart.getPaymentTransactionId(), null, idempotencyKey);
        }

        double refundedNow = remainingRefundable(cart);
        closeOutstanding(cart, false);
        cart.setRefundedAmount(cart.getTotalPrice());
        cart.setStatus(OrderStatus.CANCELLED);
        cart.setCancelReason(reasonCode);
        cart.setCancelNote(cancelNote);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.CANCELLED);
        sendNotification("Order cancelled successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Refunded: " + refundedNow);
        notifyCustomer(result, OrderStatus.CANCELLED, null);
        return result;
    }

    // Returns can only happen AFTER delivery, unlike cancel() which only works on a still-PLACED order - the two
    // are mutually exclusive by status, never overlapping windows. Otherwise the same fail-safe refund-then-
    // restore-stock shape as cancel(): a declined refund leaves the order exactly DELIVERED, nothing rolled back.
    // Lets the buyer (or admin) change the delivery window and/or instructions until the order ships. A parameter that
    // is null leaves that field as it is; a blank one clears it. Both go through the same validation as checkout, and
    // nothing is saved if either is invalid.
    public Cart rescheduleDelivery(long orderId, String deliverySlot, String deliveryNote) {
        if (deliverySlot == null && deliveryNote == null) {
            throw new ProductException("Provide a delivery slot and/or delivery instructions to change");
        }
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Delivery details can only be changed before the order ships");
        }
        // The normalizers read from and write to a cart, so run them on a scratch copy first: an invalid value must
        // not leave the real order half-updated.
        Cart scratch = new Cart();
        scratch.setDeliverySlot(deliverySlot != null ? deliverySlot : cart.getDeliverySlot());
        scratch.setDeliveryNote(deliveryNote != null ? deliveryNote : cart.getDeliveryNote());
        normalizeDeliverySlot(scratch);
        normalizeDeliveryNote(scratch);
        cart.setDeliverySlot(scratch.getDeliverySlot());
        cart.setDeliveryNote(scratch.getDeliveryNote());
        Cart saved = orderRepository.save(cart);
        sendNotification("Delivery details changed. OrderId: " + saved.getOrderId());
        return saved;
    }

    public CancellationReport getCancellationReport() {
        java.util.Map<String, Integer> byReason = new java.util.LinkedHashMap<>();
        CANCEL_REASONS.forEach(r -> byReason.put(r, 0));
        byReason.put("NOT_GIVEN", 0);
        List<CancellationReport.Note> notes = new ArrayList<>();
        int total = 0;
        List<Cart> cancelled = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)   // null status (legacy rows) is simply not CANCELLED
                .sorted(Comparator.comparing(Cart::getOrderId).reversed())
                .toList();
        for (Cart order : cancelled) {
            total++;
            byReason.merge(order.getCancelReason() == null ? "NOT_GIVEN" : order.getCancelReason(), 1, Integer::sum);
            if (order.getCancelNote() != null && notes.size() < 20) {
                notes.add(new CancellationReport.Note(order.getOrderId(), order.getCancelReason(), order.getCancelNote()));
            }
        }
        return new CancellationReport(total, byReason, notes);
    }

    public Cart returnOrder(long orderId, String authorization, String idempotencyKey, String reason) {
        return returnOrder(orderId, authorization, idempotencyKey, reason, null, null);
    }

    // payerPhno/payerPin mirror cancel()'s storefront path - the customer-facing return button has no stored
    // session token either, only a phone+PIN entered fresh for this one call (see resolveBuyerToken).
    public Cart returnOrder(long orderId, String authorization, String idempotencyKey, String reason, Long payerPhno, String payerPin) {
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A return reason is required");
        }
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.DELIVERED) {
            throw new ProductException("Only a delivered order can be returned");
        }
        // Same CASH exception as cancel() above - never charged, so nothing to refund.
        if (cart.getPaymentMethod() != PaymentMethod.CASH && cart.getPaymentTransactionId() == null) {
            throw new ProductException("This order cannot be returned");
        }

        requireWithinReturnWindow(orderId);

        if (cart.getPaymentMethod() != PaymentMethod.CASH) {
            String token = resolveBuyerToken(authorization, payerPhno, payerPin);
            refund(token, cart.getPaymentTransactionId(), null, idempotencyKey);
        }

        double refundedNow = remainingRefundable(cart);
        closeOutstanding(cart, true);
        cart.setRefundedAmount(cart.getTotalPrice());
        cart.setStatus(OrderStatus.RETURNED);
        cart.setReturnReason(reason);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.RETURNED);
        clawBackLoyaltyPoints(result, null);
        sendNotification("Order returned successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Reason: " + reason
                + " Refunded: " + refundedNow);
        notifyCustomer(result, OrderStatus.RETURNED, null);
        return result;
    }

    // Measured from the order's latest DELIVERED tracking event; an order with none (delivered before tracking
    // existed) skips the check rather than being blocked forever.
    private void requireWithinReturnWindow(long orderId) {
        Instant deliveredAt = trackingEventRepository.findByOrderIdOrderByTimestampAsc(orderId).stream()
                .filter(e -> e.getStatus() == OrderStatus.DELIVERED)
                .map(TrackingEvent::getTimestamp)
                .reduce((first, second) -> second) // latest DELIVERED event, in case of any anomaly
                .orElse(null);
        if (deliveredAt != null && deliveredAt.isBefore(Instant.now().minus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new ProductException("Return window of " + RETURN_WINDOW_DAYS + " days has expired");
        }
    }

    // Cancels some units of one item on a still-PLACED order: refunds that item's share of what was paid (or, for a
    // cash order, takes it off what's due) and puts those units back in stock. Cancelling the last outstanding
    // units makes the whole order CANCELLED, exactly as cancel() would have. Same buyer-credential rules as cancel().
    public Cart cancelItem(long orderId, int productId, int quantity, String authorization, String idempotencyKey,
                           Long payerPhno, String payerPin) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Only items of a placed order can be cancelled");
        }
        OrderItem item = itemToAdjust(cart, productId, quantity);
        double refundAmount = partialRefundAmount(cart, item, quantity);
        refundPartOfOrder(cart, refundAmount, authorization, idempotencyKey, payerPhno, payerPin, "cancelled");

        item.setCancelledQuantity(item.getCancelledQuantity() + quantity);
        productClient.updateProductStock(serviceApiKey, productId, quantity);
        cart.setRefundedAmount(roundMoney(cart.getRefundedAmount() + refundAmount));
        boolean nothingLeft = cart.getOrderItems().stream().allMatch(i -> i.getOutstandingQuantity() == 0);
        if (nothingLeft) {
            cart.setStatus(OrderStatus.CANCELLED);
        }
        Cart result = orderRepository.save(cart);
        if (nothingLeft) {
            recordTracking(result.getOrderId(), OrderStatus.CANCELLED);
        }
        sendNotification("Order item cancelled. OrderId: " + result.getOrderId() + " Product: " + productId
                + " Quantity: " + quantity + " Customer: " + mask(result.getCustomerPhno()) + " Refunded: " + refundAmount);
        if (nothingLeft) {
            notifyCustomer(result, OrderStatus.CANCELLED, null);
        } else {
            notifyItemRefund(result, "cancelled", productId, quantity, refundAmount);
        }
        return result;
    }

    // Returns some units of one item from a DELIVERED order inside the return window - the per-item counterpart of
    // returnOrder(), with the same refund/credential rules. Also claws back that item's share of the loyalty
    // points the order earned. Returning the last outstanding units makes the whole order RETURNED.
    public Cart returnItem(long orderId, int productId, int quantity, String reason, String authorization,
                           String idempotencyKey, Long payerPhno, String payerPin) {
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A return reason is required");
        }
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.DELIVERED) {
            throw new ProductException("Only items of a delivered order can be returned");
        }
        OrderItem item = itemToAdjust(cart, productId, quantity);
        requireWithinReturnWindow(orderId);
        double share = itemShare(cart, item, quantity);
        double refundAmount = partialRefundAmount(cart, item, quantity);
        refundPartOfOrder(cart, refundAmount, authorization, idempotencyKey, payerPhno, payerPin, "returned");

        item.setReturnedQuantity(item.getReturnedQuantity() + quantity);
        item.setReturnReason(reason.trim());
        productClient.updateProductStock(serviceApiKey, productId, quantity);
        cart.setRefundedAmount(roundMoney(cart.getRefundedAmount() + refundAmount));
        boolean nothingLeft = cart.getOrderItems().stream().allMatch(i -> i.getOutstandingQuantity() == 0);
        if (nothingLeft) {
            cart.setStatus(OrderStatus.RETURNED);
            cart.setReturnReason(reason.trim());
        }
        Cart result = orderRepository.save(cart);
        if (nothingLeft) {
            recordTracking(result.getOrderId(), OrderStatus.RETURNED);
        }
        clawBackLoyaltyPoints(result, nothingLeft ? null : share);
        sendNotification("Order item returned. OrderId: " + result.getOrderId() + " Product: " + productId
                + " Quantity: " + quantity + " Customer: " + mask(result.getCustomerPhno())
                + " Reason: " + reason.trim() + " Refunded: " + refundAmount);
        if (nothingLeft) {
            notifyCustomer(result, OrderStatus.RETURNED, null);
        } else {
            notifyItemRefund(result, "returned", productId, quantity, refundAmount);
        }
        return result;
    }

    private OrderItem itemToAdjust(Cart cart, int productId, int quantity) {
        if (quantity < 1) {
            throw new ProductException("Quantity must be at least 1");
        }
        OrderItem item = cart.getOrderItems().stream()
                .filter(i -> i.getProductId() == productId)
                .findFirst()
                .orElseThrow(() -> new ProductException("That product is not in this order"));
        if (quantity > item.getOutstandingQuantity()) {
            throw new ProductException("Only " + item.getOutstandingQuantity() + " of that item can still be changed");
        }
        // Without what each unit cost at the time, an item's share of the (possibly discounted) total can't be
        // worked out - older orders can still be cancelled/returned whole.
        if (cart.getOrderItems().stream().anyMatch(i -> i.getUnitPrice() == null)) {
            throw new ProductException("This order was placed before per-item changes were possible - cancel or return the whole order instead");
        }
        return item;
    }

    // This item-quantity's fraction of the order's undiscounted value.
    private double itemShare(Cart cart, OrderItem item, int quantity) {
        double orderGross = cart.getOrderItems().stream().mapToDouble(i -> i.getUnitPrice() * i.getProductQuantity()).sum();
        return orderGross <= 0 ? 0 : item.getUnitPrice() * quantity / orderGross;
    }

    // The item's share of what was actually paid, so coupon and points discounts are shared out proportionally.
    // When this takes the last outstanding units, it is exactly what's left instead - rounding can never leave a
    // few paise stuck or refund a few too many.
    private double partialRefundAmount(Cart cart, OrderItem item, int quantity) {
        double remaining = remainingRefundable(cart);
        int outstandingAfter = cart.getOrderItems().stream().mapToInt(OrderItem::getOutstandingQuantity).sum() - quantity;
        if (outstandingAfter == 0) {
            return remaining;
        }
        return Math.min(roundMoney(cart.getTotalPrice() * itemShare(cart, item, quantity)), remaining);
    }

    private double remainingRefundable(Cart cart) {
        return Math.max(0, roundMoney(cart.getTotalPrice() - cart.getRefundedAmount()));
    }

    // Only a PhonePe order has money to send back; a cash order just owes less (recorded by the caller).
    private void refundPartOfOrder(Cart cart, double amount, String authorization, String idempotencyKey,
                                   Long payerPhno, String payerPin, String what) {
        if (cart.getPaymentMethod() == PaymentMethod.CASH || amount <= 0) {
            return;
        }
        if (cart.getPaymentTransactionId() == null) {
            throw new ProductException("This item cannot be " + what);
        }
        String token = resolveBuyerToken(authorization, payerPhno, payerPin);
        refund(token, cart.getPaymentTransactionId(), BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), idempotencyKey);
    }

    // Whole-order cancel/return: puts back in stock only what earlier per-item changes haven't already, and
    // records those units as cancelled/returned so every item ends with nothing outstanding.
    private void closeOutstanding(Cart cart, boolean returned) {
        for (OrderItem orderItem : cart.getOrderItems()) {
            int outstanding = orderItem.getOutstandingQuantity();
            if (outstanding <= 0) {
                continue;
            }
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), outstanding);
            if (returned) {
                orderItem.setReturnedQuantity(orderItem.getReturnedQuantity() + outstanding);
            } else {
                orderItem.setCancelledQuantity(orderItem.getCancelledQuantity() + outstanding);
            }
        }
    }

    private static double roundMoney(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // What the customer is still actually paying for: nothing once cancelled/returned (older returned orders
    // predate refundedAmount, so status decides) or while a UPI payment is still awaiting approval, otherwise the
    // total minus any per-item refunds.
    private static double netPaid(Cart cart) {
        if (cart.getStatus() == OrderStatus.CANCELLED || cart.getStatus() == OrderStatus.RETURNED
                || cart.getStatus() == OrderStatus.PENDING_PAYMENT) {
            return 0;
        }
        return Math.max(0, cart.getTotalPrice() - cart.getRefundedAmount());
    }

    // No code, no discount - the common case. A code that doesn't match any Coupon, or matches one that's been
    // deactivated, must fail loudly rather than silently charging full price (a buyer trusting a "10% off"
    // banner should never find out only after being charged in full).
    static final int MAX_DELIVERY_NOTE_LENGTH = 200;

    // Blank -> null; anything longer than the limit is rejected up front (before any payment), not truncated.
    private void normalizeDeliveryNote(Cart cart) {
        String note = cart.getDeliveryNote();
        if (note == null || note.isBlank()) {
            cart.setDeliveryNote(null);
            return;
        }
        note = note.trim();
        if (note.length() > MAX_DELIVERY_NOTE_LENGTH) {
            throw new ProductException("Delivery instructions must be at most " + MAX_DELIVERY_NOTE_LENGTH + " characters");
        }
        cart.setDeliveryNote(note);
    }

    private double resolveDiscount(Cart cart, double price) {
        String code = cart.getCouponCode();
        if (code == null || code.isBlank()) {
            cart.setCouponCode(null);
            return 0;
        }
        String normalized = code.trim().toUpperCase();
        Coupon coupon = couponRepository.findById(normalized)
                .orElseThrow(() -> new ProductException("Invalid coupon code"));
        if (!coupon.isActive()) {
            throw new ProductException("Coupon is no longer active");
        }
        if (coupon.getExpiryDate() != null && Instant.now().isAfter(coupon.getExpiryDate())) {
            throw new ProductException("Coupon has expired");
        }
        if (coupon.getMaxRedemptions() != null && coupon.getRedemptionCount() >= coupon.getMaxRedemptions()) {
            throw new ProductException("Coupon has reached its redemption limit");
        }
        if (coupon.getPerCustomerLimit() != null) {
            int alreadyUsed = couponRedemptionRepository
                    .findByCouponCodeAndCustomerPhno(normalized, cart.getCustomerPhno())
                    .map(CouponRedemption::getCount)
                    .orElse(0);
            if (alreadyUsed >= coupon.getPerCustomerLimit()) {
                throw new ProductException("You have already used this coupon the maximum number of times");
            }
        }
        cart.setCouponCode(normalized);
        return price * coupon.getDiscountPercent() / 100.0;
    }

    // No points requested - the common case - is free. A request that exceeds the customer's actual balance, or
    // exceeds what's left to pay after any coupon discount, fails loudly rather than silently capping it: the
    // same "a buyer must never be charged something other than what they were quoted" reasoning as resolveDiscount.
    private double resolvePointsRedemption(Cart cart, double remainingPrice) {
        Integer requested = cart.getPointsRedeemed();
        if (requested == null || requested == 0) {
            cart.setPointsRedeemed(null);
            return 0;
        }
        if (requested < 0) {
            throw new ProductException("Points redeemed cannot be negative");
        }
        int balance = loadLoyaltyAccount(cart.getCustomerPhno()).getPointsBalance();
        if (requested > balance) {
            throw new ProductException("You only have " + balance + " loyalty points available");
        }
        // 1 point = ₹1, same flat conversion PriceHistory/coupon-percent style code elsewhere in this codebase
        // keeps simple rather than configurable, since nothing here requires it to vary.
        if (requested > remainingPrice) {
            throw new ProductException("Cannot redeem more points than the order total after any coupon discount");
        }
        return requested;
    }

    // Only called after a successful charge (see order()) - a failed/declined payment must not consume points,
    // same reasoning recordCouponRedemption already follows for coupon usage.
    private void redeemLoyaltyPoints(long customerPhno, Integer pointsRedeemed, long orderId) {
        if (pointsRedeemed == null || pointsRedeemed == 0) {
            return;
        }
        LoyaltyAccount account = loadLoyaltyAccount(customerPhno);
        account.setPointsBalance(account.getPointsBalance() - pointsRedeemed);
        account.setLastActivityAt(Instant.now());
        loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(customerPhno, orderId, -pointsRedeemed, LoyaltyTransactionType.REDEEMED, null);
    }

    // Earned once an order actually reaches DELIVERED (see deliver() below) rather than at order()/PLACED time -
    // a cancelled-before-delivery or returned order never earns anything, same "only a completed sale counts"
    // reasoning recordCouponRedemption applies to coupon usage.
    private static final int RUPEES_PER_POINT = 10;

    // The multiplier applied is the tier the customer was in BEFORE this order's points are added - so crossing
    // a tier threshold takes effect starting with the customer's NEXT order, not retroactively on the one that
    // crossed it.
    private void earnLoyaltyPoints(Cart cart) {
        // Net of any items cancelled before delivery - those were never bought.
        int baseEarned = (int) (netPaid(cart) / RUPEES_PER_POINT);
        if (baseEarned <= 0) {
            return;
        }
        LoyaltyAccount account = loadLoyaltyAccount(cart.getCustomerPhno());
        LoyaltyTier tier = LoyaltyTier.forLifetimePoints(account.getLifetimePointsEarned());
        int earned = (int) (baseEarned * tier.getEarnMultiplier());
        account.setPointsBalance(account.getPointsBalance() + earned);
        account.setLifetimePointsEarned(account.getLifetimePointsEarned() + earned);
        account.setLastActivityAt(Instant.now());
        loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(cart.getCustomerPhno(), cart.getOrderId(), earned, LoyaltyTransactionType.EARNED, null);
    }

    // Reverses the EXACT amount earned at deliver() time when that same order is later returned - looked up from
    // that order's own EARNED transaction rather than recomputed, since the tier multiplier in effect back then
    // may differ from the multiplier in effect now. Clamped at zero rather than going negative: the customer may
    // have already spent those points on a different order in the meantime, and this system has no notion of a
    // customer owing points back. lifetimePointsEarned is reduced by the same clamped amount - a returned order
    // must not count toward tier progress any more than it counts toward the spendable balance.
    //
    // share is the returned item's fraction of the order (a per-item return claws back that fraction of what was
    // earned), or null to claw back everything not already clawed back by earlier per-item returns.
    private void clawBackLoyaltyPoints(Cart cart, Double share) {
        LoyaltyTransaction earnedTx = loyaltyTransactionRepository
                .findByOrderIdAndType(cart.getOrderId(), LoyaltyTransactionType.EARNED)
                .orElse(null);
        if (earnedTx == null || earnedTx.getPoints() <= 0) {
            return;
        }
        int earned = earnedTx.getPoints();
        int alreadyClawed = -loyaltyTransactionRepository
                .findAllByOrderIdAndType(cart.getOrderId(), LoyaltyTransactionType.ADJUSTED).stream()
                .mapToInt(LoyaltyTransaction::getPoints).filter(p -> p < 0).sum();
        int stillEarned = Math.max(0, earned - alreadyClawed);
        int target = share == null ? stillEarned : Math.min(stillEarned, (int) Math.floor(earned * share));
        if (target <= 0) {
            return;
        }
        LoyaltyAccount account = loadLoyaltyAccount(cart.getCustomerPhno());
        int clawedBack = Math.min(target, account.getPointsBalance());
        if (clawedBack <= 0) {
            return;
        }
        account.setPointsBalance(account.getPointsBalance() - clawedBack);
        account.setLifetimePointsEarned(Math.max(0, account.getLifetimePointsEarned() - clawedBack));
        account.setLastActivityAt(Instant.now());
        loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(cart.getCustomerPhno(), cart.getOrderId(), -clawedBack, LoyaltyTransactionType.ADJUSTED,
                "Points earned on order #" + cart.getOrderId() + " reversed after " + (share == null ? "return" : "item return"));
    }

    private LoyaltyAccount newLoyaltyAccount(long customerPhno) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setCustomerPhno(customerPhno);
        account.setPointsBalance(0);
        return account;
    }

    // ~12 months - Instant has no calendar-month arithmetic (ChronoUnit.MONTHS isn't a supported unit for it,
    // unlike LocalDate), so this is expressed in days instead.
    private static final int POINTS_EXPIRY_DAYS = LoyaltyAccount.POINTS_EXPIRY_DAYS;

    // The single entry point every loyalty method should load an account through - applies expiry BEFORE
    // handing back the balance, so a stale balance is never read, redeemed against, or added to without first
    // being zeroed. This system has no scheduler (see getPriceDropAlerts()'s same caveat), so expiry isn't a
    // background sweep - it's checked lazily, the moment anything next touches the account.
    private LoyaltyAccount loadLoyaltyAccount(long customerPhno) {
        LoyaltyAccount account = loyaltyAccountRepository.findById(customerPhno)
                .orElseGet(() -> newLoyaltyAccount(customerPhno));
        applyPointsExpiry(account);
        return account;
    }

    // A balance with no activity in POINTS_EXPIRY_DAYS expires entirely - not a background job (see
    // loadLoyaltyAccount()), so this only ever fires the next time the account is read or touched. Expiring
    // itself is not "activity" and must not reset lastActivityAt, or a balance sitting at zero would just get
    // silently re-armed forever; the balance being zero already after expiry naturally prevents this method
    // from re-firing on the same stale timestamp. lifetimePointsEarned (and therefore tier) is untouched -
    // expiry is about the spendable balance only, not a customer's earned history.
    private void applyPointsExpiry(LoyaltyAccount account) {
        if (account.getPointsBalance() <= 0 || account.getLastActivityAt() == null) {
            return;
        }
        if (Instant.now().isBefore(account.getLastActivityAt().plus(POINTS_EXPIRY_DAYS, ChronoUnit.DAYS))) {
            return;
        }
        int expired = account.getPointsBalance();
        account.setPointsBalance(0);
        loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(account.getCustomerPhno(), null, -expired, LoyaltyTransactionType.EXPIRED,
                "Points expired after " + POINTS_EXPIRY_DAYS + " days of no account activity");
    }

    private void recordLoyaltyTransaction(long customerPhno, Long orderId, int points, LoyaltyTransactionType type, String reason) {
        LoyaltyTransaction transaction = new LoyaltyTransaction();
        transaction.setCustomerPhno(customerPhno);
        transaction.setOrderId(orderId);
        transaction.setPoints(points);
        transaction.setType(type);
        transaction.setReason(reason);
        transaction.setTimestamp(Instant.now());
        loyaltyTransactionRepository.save(transaction);
    }

    public LoyaltyAccount getLoyaltyAccount(long phno) {
        validatePhno(phno);
        return loadLoyaltyAccount(phno);
    }

    public List<LoyaltyTransaction> getLoyaltyHistory(long phno) {
        validatePhno(phno);
        return loyaltyTransactionRepository.findByCustomerPhnoOrderByTimestampDesc(phno);
    }

    // Admin correction path (e.g. a goodwill credit, or fixing a mistaken accrual) - not tied to any order, so
    // orderId is null on the resulting transaction, same as any other ADJUSTED entry.
    public LoyaltyAccount adjustLoyaltyPoints(long phno, int points, String reason) {
        validatePhno(phno);
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A reason is required for a manual points adjustment");
        }
        LoyaltyAccount account = loadLoyaltyAccount(phno);
        int newBalance = account.getPointsBalance() + points;
        if (newBalance < 0) {
            throw new ProductException("Adjustment would leave a negative points balance");
        }
        account.setPointsBalance(newBalance);
        account.setLastActivityAt(Instant.now());
        LoyaltyAccount saved = loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(phno, null, points, LoyaltyTransactionType.ADJUSTED, reason);
        return saved;
    }

    // Only called after a successful charge (see order()) - a failed/declined payment must not consume a
    // redemption, the same reasoning stock decrements and tracking events already follow.
    private void recordCouponRedemption(String couponCode, long customerPhno) {
        if (couponCode == null) {
            return;
        }
        couponRepository.findById(couponCode).ifPresent(coupon -> {
            coupon.setRedemptionCount(coupon.getRedemptionCount() + 1);
            couponRepository.save(coupon);
        });
        CouponRedemption redemption = couponRedemptionRepository
                .findByCouponCodeAndCustomerPhno(couponCode, customerPhno)
                .orElseGet(() -> {
                    CouponRedemption r = new CouponRedemption();
                    r.setCouponCode(couponCode);
                    r.setCustomerPhno(customerPhno);
                    return r;
                });
        redemption.setCount(redemption.getCount() + 1);
        couponRedemptionRepository.save(redemption);
    }

    // No address on the cart is fine - it's optional. One that IS set must actually exist and belong to the
    // customer placing the order; otherwise a typo'd or someone-else's address id would silently ship to the
    // wrong place with no error at all.
    private void validateShippingAddress(Cart cart) {
        Long addressId = cart.getShippingAddressId();
        if (addressId == null) {
            return;
        }
        ShippingAddress address = shippingAddressRepository.findById(addressId)
                .orElseThrow(() -> new OrderNotFoundException("Address not found"));
        if (address.getCustomerPhno() != cart.getCustomerPhno()) {
            throw new OrderNotFoundException("Address not found");
        }
        // Same fail-before-payment rule: don't take money for an order we've said we don't deliver to. Skipped
        // while no pincodes are configured at all (see ServiceablePincode).
        if (serviceablePincodeRepository.count() > 0
                && !serviceablePincodeRepository.existsById(String.valueOf(address.getPincode()).trim())) {
            throw new ProductException("Sorry, we don't deliver to pincode " + address.getPincode() + " yet");
        }
    }

    // Time windows a buyer can ask for at checkout (optional). Stored as the key; the label is for display only.
    static final Map<String, String> DELIVERY_SLOTS = new java.util.LinkedHashMap<>();
    static {
        DELIVERY_SLOTS.put("MORNING", "Morning (9am-12pm)");
        DELIVERY_SLOTS.put("AFTERNOON", "Afternoon (12pm-4pm)");
        DELIVERY_SLOTS.put("EVENING", "Evening (4pm-8pm)");
    }

    // Blank -> null; anything not in DELIVERY_SLOTS is rejected up front rather than saved as free text.
    private void normalizeDeliverySlot(Cart cart) {
        String slot = cart.getDeliverySlot();
        if (slot == null || slot.isBlank()) {
            cart.setDeliverySlot(null);
            return;
        }
        slot = slot.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DELIVERY_SLOTS.containsKey(slot)) {
            throw new ProductException("Unknown delivery slot. Choose one of: " + String.join(", ", DELIVERY_SLOTS.keySet()));
        }
        cart.setDeliverySlot(slot);
    }

    public List<String> getDeliverySlots() {
        return new ArrayList<>(DELIVERY_SLOTS.keySet());
    }

    private static String normalizePincode(String raw) {
        String p = raw == null ? "" : raw.trim();
        if (!p.matches("[1-9][0-9]{5}")) {
            throw new ProductException("Pincode must be 6 digits");
        }
        return p;
    }

    public PincodeServiceability checkPincode(String pincode) {
        String p = normalizePincode(pincode);
        if (serviceablePincodeRepository.count() == 0) {
            return new PincodeServiceability(p, true, null);
        }
        return serviceablePincodeRepository.findById(p)
                .map(sp -> new PincodeServiceability(p, true, sp.getDeliveryDays()))
                .orElse(new PincodeServiceability(p, false, null));
    }

    public ServiceablePincode savePincode(ServiceablePincode pincode) {
        pincode.setPincode(normalizePincode(pincode.getPincode()));
        if (pincode.getDeliveryDays() < 1 || pincode.getDeliveryDays() > 30) {
            throw new ProductException("Delivery days must be between 1 and 30");
        }
        return serviceablePincodeRepository.save(pincode);
    }

    public void removePincode(String pincode) {
        String p = normalizePincode(pincode);
        if (!serviceablePincodeRepository.existsById(p)) {
            throw new ProductException("Pincode " + p + " is not in the serviceable list");
        }
        serviceablePincodeRepository.deleteById(p);
    }

    public List<ServiceablePincode> getPincodes() {
        return serviceablePincodeRepository.findAll();
    }

    public Coupon saveCoupon(Coupon coupon) {
        if (coupon.getCode() == null || coupon.getCode().isBlank()) {
            throw new ProductException("Coupon code is required");
        }
        if (coupon.getDiscountPercent() <= 0 || coupon.getDiscountPercent() > 100) {
            throw new ProductException("Discount percent must be between 0 and 100");
        }
        if (coupon.getMaxRedemptions() != null && coupon.getMaxRedemptions() <= 0) {
            throw new ProductException("Max redemptions must be positive");
        }
        if (coupon.getPerCustomerLimit() != null && coupon.getPerCustomerLimit() <= 0) {
            throw new ProductException("Per-customer limit must be positive");
        }
        String normalizedCode = coupon.getCode().trim().toUpperCase();
        coupon.setCode(normalizedCode);
        // redemptionCount is system-managed (see recordCouponRedemption) - preserve it across an update instead
        // of silently resetting accumulated usage back to zero just because the caller's payload didn't include it.
        couponRepository.findById(normalizedCode)
                .ifPresent(existing -> coupon.setRedemptionCount(existing.getRedemptionCount()));
        return couponRepository.save(coupon);
    }

    // Coupons this customer could apply at checkout right now, best discount first: active, not expired, not at
    // the global redemption cap, and not already at this customer's own per-customer limit - i.e. exactly the
    // checks resolveDiscount() would pass. Note this makes every active coupon code visible to anyone who knows
    // a phone number, same self-service trust level as /cart/byphno.
    public List<CouponSuggestion> getAvailableCoupons(long phno) {
        validatePhno(phno);
        Instant now = Instant.now();
        List<CouponSuggestion> suggestions = new ArrayList<>();
        for (Coupon c : couponRepository.findAll()) {
            if (!c.isActive()) continue;
            if (c.getExpiryDate() != null && now.isAfter(c.getExpiryDate())) continue;
            if (c.getMaxRedemptions() != null && c.getRedemptionCount() >= c.getMaxRedemptions()) continue;
            Integer usesLeft = null;
            if (c.getPerCustomerLimit() != null) {
                int used = couponRedemptionRepository.findByCouponCodeAndCustomerPhno(c.getCode(), phno)
                        .map(CouponRedemption::getCount).orElse(0);
                if (used >= c.getPerCustomerLimit()) continue;
                usesLeft = c.getPerCustomerLimit() - used;
            }
            suggestions.add(new CouponSuggestion(c.getCode(), c.getDiscountPercent(), c.getExpiryDate(), usesLeft));
        }
        suggestions.sort(Comparator.comparingDouble(CouponSuggestion::discountPercent).reversed()
                .thenComparing(CouponSuggestion::code));
        return suggestions;
    }

    public List<Coupon> getCoupons() {
        return couponRepository.findAll();
    }

    // Ship/deliver form a strict one-way lifecycle on top of PLACED/CANCELLED: PLACED -> SHIPPED -> DELIVERED.
    // Neither step touches stock or payment - those were already settled at order() time - so there's nothing
    // to roll back if a later step never happens.
    public Cart ship(long orderId) {
        return ship(orderId, null, null);
    }

    static final int MAX_SHIPMENT_FIELD_LENGTH = 60;

    // Blank -> null; longer than the column is rejected rather than silently truncated.
    private static String cleanShipmentField(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > MAX_SHIPMENT_FIELD_LENGTH) {
            throw new ProductException(what + " must be at most " + MAX_SHIPMENT_FIELD_LENGTH + " characters");
        }
        return v;
    }

    // carrier / trackingNumber are optional; they are validated before the order is touched, and the shipped email
    // and the buyer's order list show them when present.
    public Cart ship(long orderId, String carrier, String trackingNumber) {
        String cleanCarrier = cleanShipmentField(carrier, "Carrier");
        String cleanTracking = cleanShipmentField(trackingNumber, "Tracking number");
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Only a placed order can be shipped");
        }
        cart.setCarrier(cleanCarrier);
        cart.setTrackingNumber(cleanTracking);
        cart.setStatus(OrderStatus.SHIPPED);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.SHIPPED);
        customerNotifier.notifyStatusChange(result, OrderStatus.SHIPPED);
        sendNotification("Order shipped. OrderId: " + result.getOrderId());
        return result;
    }

    // Corrects the carrier/tracking number of an order that is already SHIPPED (a typo, or a re-booked courier).
    // Unlike ship(), a null argument keeps the current value and a blank one clears it.
    public Cart updateShipment(long orderId, String carrier, String trackingNumber) {
        if (carrier == null && trackingNumber == null) {
            throw new ProductException("Provide a carrier and/or tracking number to change");
        }
        String cleanCarrier = cleanShipmentField(carrier, "Carrier");
        String cleanTracking = cleanShipmentField(trackingNumber, "Tracking number");
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.SHIPPED) {
            throw new ProductException("Shipment details can only be changed while the order is shipped");
        }
        if (carrier != null) {
            cart.setCarrier(cleanCarrier);
        }
        if (trackingNumber != null) {
            cart.setTrackingNumber(cleanTracking);
        }
        return orderRepository.save(cart);
    }

    public Cart deliver(long orderId) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.SHIPPED) {
            throw new ProductException("Only a shipped order can be delivered");
        }
        cart.setStatus(OrderStatus.DELIVERED);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.DELIVERED);
        earnLoyaltyPoints(result);
        customerNotifier.notifyStatusChange(result, OrderStatus.DELIVERED);
        try {
            eventPublisher.publishEvent(new OrderDeliveredEvent(result));
        } catch (RuntimeException e) {
            log.error("Order delivered listener failed for order {}: {}", result.getOrderId(), e.getMessage());
        }
        sendNotification("Order delivered. OrderId: " + result.getOrderId());
        return result;
    }

    static final int MAX_BULK_ORDERS = 100;

    // Ships (action "ship") or delivers (action "deliver") many orders in one go - the warehouse clearing a day's
    // batch. Each order goes through the normal ship()/deliver(), so every rule, tracking event, email and loyalty
    // credit is identical to doing them one by one; an order that can't move (unknown, wrong status) is reported
    // in the result and the rest carry on.
    public BulkTransitionResult bulkTransition(String action, List<Long> orderIds) {
        if (!"ship".equals(action) && !"deliver".equals(action)) {
            throw new ProductException("Unknown bulk action: " + action);
        }
        if (orderIds == null || orderIds.isEmpty()) {
            throw new ProductException("Provide at least one order id");
        }
        List<Long> distinct = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new ProductException("Provide at least one order id");
        }
        if (distinct.size() > MAX_BULK_ORDERS) {
            throw new ProductException("At most " + MAX_BULK_ORDERS + " orders per bulk action");
        }
        List<BulkTransitionResult.Item> results = new ArrayList<>();
        int succeeded = 0;
        for (Long id : distinct) {
            try {
                if ("ship".equals(action)) {
                    ship(id);
                } else {
                    deliver(id);
                }
                results.add(new BulkTransitionResult.Item(id, true, "OK"));
                succeeded++;
            } catch (OrderNotFoundException | ProductException e) {
                results.add(new BulkTransitionResult.Item(id, false, e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Bulk {} of order {} failed: {}", action, id, e.getMessage());
                results.add(new BulkTransitionResult.Item(id, false, "Unexpected error - check the order and retry"));
            }
        }
        return new BulkTransitionResult(action, succeeded, results.size() - succeeded, results);
    }

    // Records that a CASH order's money has actually been collected (e.g. the courier handed it to the customer
    // at the door) - deliberately a separate, explicit ops action rather than something deliver() sets
    // automatically, since a delivery and a successful cash collection aren't the same event (a courier can
    // deliver without collecting, or collect after a short delay) - this system has no scheduler or payment
    // provider to reconcile that gap automatically, so an admin/ops caller records it once it's actually true. A
    // PHONEPE order is already paid the moment its charge succeeds (see order()), so marking it "paid" again
    // makes no sense and is rejected instead of silently accepted.
    public Cart markPaid(long orderId) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getPaymentMethod() != PaymentMethod.CASH) {
            throw new ProductException("Only a cash-on-delivery order can be marked paid this way");
        }
        if (cart.isPaid()) {
            throw new ProductException("This order is already marked paid");
        }
        cart.setPaid(true);
        Cart result = orderRepository.save(cart);
        sendNotification("Order marked paid (cash collected). OrderId: " + result.getOrderId());
        return result;
    }

    // Appends one row to the order's tracking timeline. Called only after the Cart's own status has already been
    // saved, same ordering as sendNotification() below - a broken write here must never look like the status
    // change itself failed, so it's logged and swallowed rather than propagated.
    private void recordTracking(long orderId, OrderStatus status) {
        try {
            TrackingEvent event = new TrackingEvent();
            event.setOrderId(orderId);
            event.setStatus(status);
            event.setTimestamp(Instant.now());
            trackingEventRepository.save(event);
        } catch (RuntimeException e) {
            log.error("Failed to record tracking event for order {}: {}", orderId, e.getMessage());
        }
    }

    // Receipt for one order. The caller must supply the phone number the order was placed under - a mismatch is
    // reported as "not found" (same as an unknown id) so an order id alone can't be used to read someone else's
    // receipt. A product whose catalog lookup fails (removed since) is shown as "Product #id" at price 0.
    public long ownerPhnoOf(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"))
                .getCustomerPhno();
    }

    public GuestOrderSummary getGuestSummary(long orderId, long phno) {
        Cart order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        return new GuestOrderSummary(order.getOrderId(), String.valueOf(order.getStatus()), order.getTotalPrice(),
                String.valueOf(order.getPaymentMethod()), order.isPaid());
    }

    public Invoice getInvoice(long orderId, long phno) {
        Cart order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        List<Invoice.Line> lines = new ArrayList<>();
        for (OrderItem item : order.getOrderItems()) {
            String name = "Product #" + item.getProductId();
            double unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : 0;
            try {
                Product p = productClient.getProductById(item.getProductId());
                if (p != null) {
                    name = p.getProductName();
                    if (item.getUnitPrice() == null) {
                        unitPrice = p.getProductPrice();
                    }
                }
            } catch (FeignException e) {
                // removed from the catalog - keep the fallback name/price rather than failing the whole receipt
            }
            lines.add(new Invoice.Line(item.getProductId(), name, item.getProductQuantity(), unitPrice,
                    unitPrice * item.getProductQuantity(), item.getCancelledQuantity(), item.getReturnedQuantity()));
        }
        Instant placedAt = trackingEventRepository.findByOrderIdOrderByTimestampAsc(orderId).stream()
                .map(TrackingEvent::getTimestamp).findFirst().orElse(null);
        String address = null;
        if (order.getShippingAddressId() != null) {
            address = shippingAddressRepository.findById(order.getShippingAddressId())
                    .map(a -> String.join(", ", a.getLine1(), a.getCity(), a.getState(), a.getPincode()))
                    .orElse(null);
        }
        return new Invoice(orderId, placedAt, order.getCustomerName(), order.getCustomerPhno(), lines,
                order.getCouponCode(), order.getDiscountAmount(),
                order.getPointsRedeemed() == null ? 0 : order.getPointsRedeemed(), order.getTotalPrice(),
                order.getRefundedAmount(),
                String.valueOf(order.getPaymentMethod()), order.isPaid(), String.valueOf(order.getStatus()), address,
                order.getDeliveryNote(), order.getDeliverySlot());
    }

    public List<TrackingEvent> getTracking(long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException("Order not found");
        }
        return trackingEventRepository.findByOrderIdOrderByTimestampAsc(orderId);
    }

    // The SHIPPED/DELIVERED notifications CustomerNotifier recorded for this order (emailed or recorded-only).
    public List<NotificationLog> getNotifications(long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException("Order not found");
        }
        return notificationLogRepository.findByOrderIdOrderBySentAtAsc(orderId);
    }

    // Backs the storefront's "My notifications" panel - every notification across all of a customer's own
    // orders, newest first, same self-service trust level as /cart/byphno (a customer's own phone number is
    // already how every other "my own data" lookup in this system is scoped).
    public List<NotificationLog> getNotificationsForCustomer(long phno) {
        validatePhno(phno);
        List<Long> orderIds = orderRepository.findBycustomerPhno(phno).stream()
                .map(Cart::getOrderId)
                .toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return notificationLogRepository.findByOrderIdInOrderBySentAtDesc(orderIds);
    }

    // Emails the customer (see CustomerNotifier) about an order change they or the system just caused. Called only
    // after the change is saved, and a failure here is logged, never propagated - same rule as sendNotification().
    private void notifyCustomer(Cart order, OrderStatus status, String detail) {
        try {
            customerNotifier.notifyStatusChange(order, status, detail);
        } catch (RuntimeException e) {
            log.error("Customer notification failed for order {} ({}): {}", order.getOrderId(), status, e.getMessage());
        }
    }

    private void notifyItemRefund(Cart order, String what, int productId, int quantity, double refunded) {
        try {
            customerNotifier.notifyItemRefund(order, what, productId, quantity, refunded);
        } catch (RuntimeException e) {
            log.error("Customer notification failed for order {} (item {}): {}", order.getOrderId(), what, e.getMessage());
        }
    }

    // Kafka is told only AFTER the save() above returns - neither order() nor cancel() wraps its DB writes in a
    // surrounding @Transactional, so by that point the write has already committed (each repository.save() is
    // its own auto-committed transaction). A broken notification channel must never turn a completed order/
    // cancellation into an error, so failures here are logged and swallowed, not propagated.
    private void sendNotification(String message) {
        try {
            orderKafkaProducer.sendMessage(message);
        } catch (RuntimeException e) {
            log.error("Kafka notification failed: {}", e.getMessage());
        }
    }

    // keep phone numbers out of the Kafka topic and its logs
    private static String mask(long phno) {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }

    private static final String COMPLETED = "COMPLETED";
    private static final int RETURN_WINDOW_DAYS = 7;

    // An already-supplied token (the admin dashboard, or any caller that logged into PhonepayService itself)
    // always wins - never re-authenticate behind the caller's back. Only when there's no token at all does the
    // storefront's phone+PIN get used, exchanged for a fresh token via PhonepayService's own /phonepe/login -
    // OrderService never checks the PIN itself, so a wrong PIN surfaces as PhonepayService's own 401/423, not a
    // silently-generic OrderService error.
    private String resolveBuyerToken(String authorization, Long payerPhno, String payerPin) {
        if (authorization != null && !authorization.isBlank()) {
            return authorization;
        }
        if (payerPhno == null || payerPin == null || payerPin.isBlank()) {
            throw new ProductException("PhonePe payment requires either an Authorization token or a phone number and PIN");
        }
        PhonepeLoginResponse login;
        try {
            login = phonepeClient.login(new PhonepeLoginRequest(payerPhno, payerPin));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        return "Bearer " + login.token();
    }

    // Pure proxy to PhonepayService (which itself proxies to Bankapplication) - shop.html has no way to call
    // either of those origins directly. Same FeignException translation as resolveBuyerToken above.
    public void forgotPinRequest(long phno) {
        try {
            phonepeClient.forgotPinRequest(new PhonepeForgotPinRequest(phno));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
    }

    public void resetPin(long phno, String otp, String newPin) {
        try {
            phonepeClient.forgotPinReset(new PhonepeResetPinRequest(phno, otp, newPin));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
    }

    private PaymentResponse charge(String authorization, double price, String idempotencyKey) {
        BigDecimal amount = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        PaymentResponse payment;
        try {
            payment = phonepeClient.makePayment(authorization, new PaymentRequest(amount, "Order payment", idempotencyKey));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        // A fresh payment attempt only ever returns here once truly COMPLETED (anything else throws on
        // PhonepayService's side). But a retry that reuses an Idempotency-Key returns whatever status that
        // earlier attempt ended up with - including NEEDS_RECONCILIATION - with no exception at all, so a
        // 200 response alone is not proof the money actually moved; the status field has to be checked too.
        if (!COMPLETED.equals(payment.status())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY,
                    "Payment status is " + payment.status() + ", not completed. Reference: " + payment.transactionId());
        }
        return payment;
    }

    // amount null = whatever is still unrefunded on the payment (see PhonepayService's partial refunds).
    private void refund(String authorization, long paymentTransactionId, BigDecimal amount, String idempotencyKey) {
        PaymentResponse refund;
        try {
            refund = phonepeClient.refund(authorization, paymentTransactionId, new RefundRequest(idempotencyKey, amount));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        // Same reasoning as charge(): an Idempotency-Key retry can hand back a NEEDS_RECONCILIATION refund
        // without ever throwing, so stock must not be restored until the status itself confirms completion.
        if (!COMPLETED.equals(refund.status())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY,
                    "Refund status is " + refund.status() + ", not completed. Reference: " + refund.transactionId());
        }
    }
    public List<Cart>  findAll()
    {
        return orderRepository.findAll();
    }
    public List<Cart> ordersOfPhno(long phno)
    {
        validatePhno(phno);
        return orderRepository.findBycustomerPhno(phno);
    }
    static final int MAX_HISTORY_PAGE_SIZE = 50;

    // A customer's own orders, newest first, optionally narrowed by status and by the day the order was placed
    // (UTC, inclusive both ends - same convention as the admin searchOrders()), then paged. Filtering is in memory:
    // one customer's orders are few, unlike the admin search over everyone's.
    public OrderHistoryPage getOrderHistory(long phno, String status, java.time.LocalDate from, java.time.LocalDate to,
                                            int page, int size) {
        validatePhno(phno);
        if (page < 0) {
            throw new ProductException("Page must be 0 or more");
        }
        if (size < 1 || size > MAX_HISTORY_PAGE_SIZE) {
            throw new ProductException("Page size must be between 1 and " + MAX_HISTORY_PAGE_SIZE);
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new ProductException("'to' date is before 'from' date");
        }
        OrderStatus wantedStatus = parseEnumFilter(OrderStatus.class, status, "status");
        Instant fromInstant = from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        List<Cart> matching = new ArrayList<>();
        for (Cart order : orderRepository.findBycustomerPhno(phno)) {
            if (wantedStatus != null && order.getStatus() != wantedStatus) continue;
            if (fromInstant != null || toExclusive != null) {
                Instant placedAt = trackingEventRepository.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                        .map(TrackingEvent::getTimestamp).findFirst().orElse(null);
                if (placedAt == null) continue;
                if (fromInstant != null && placedAt.isBefore(fromInstant)) continue;
                if (toExclusive != null && !placedAt.isBefore(toExclusive)) continue;
            }
            matching.add(order);
        }
        matching.sort(Comparator.comparing(Cart::getOrderId).reversed());
        int fromIndex = (int) Math.min((long) page * size, matching.size());
        int toIndex = Math.min(fromIndex + size, matching.size());
        int totalPages = (matching.size() + size - 1) / size;
        return new OrderHistoryPage(new ArrayList<>(matching.subList(fromIndex, toIndex)), page, size, matching.size(), totalPages);
    }

    public List<Product> getProducts()
    {
        List<Product> products = productClient.findAll();
        products.forEach(this::absolutizeImageUrl);
        return products;
    }

    // Images uploaded before ProductService stored absolute URLs come back as "/uploads/<file>", which the
    // storefront would resolve against THIS service's origin (a 401 here) - point them at ProductService instead.
    private static final String PRODUCT_SERVICE_ORIGIN = "http://localhost:8082";

    private void absolutizeImageUrl(Product p) {
        if (p != null) {
            p.setProductImageUrl(absolutizeImageUrl(p.getProductImageUrl()));
        }
    }

    private static String absolutizeImageUrl(String url) {
        return url != null && url.startsWith("/") ? PRODUCT_SERVICE_ORIGIN + url : url;
    }

    // ProductService's /product/search is paginated; the storefront wants the whole matching catalog (it sorts,
    // filters and pages it client-side), so walk the pages here. The page cap is only a safety net against a
    // misbehaving downstream - 50 x 200 = 10,000 products.
    private static final int SEARCH_PAGE_SIZE = 200;
    private static final int MAX_SEARCH_PAGES = 50;

    public List<Product> searchProducts(String name, String category) {
        List<Product> found = new ArrayList<>();
        for (int page = 0; page < MAX_SEARCH_PAGES; page++) {
            ProductSearchResult result = productClient.search(blankToNull(name), blankToNull(category), page, SEARCH_PAGE_SIZE);
            if (result.content() != null) {
                found.addAll(result.content());
            }
            if (result.isLastPage() || result.content() == null || result.content().isEmpty()) {
                break;
            }
        }
        found.forEach(this::absolutizeImageUrl);
        return found;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // One rating-summary lookup per id, same N-calls-in-a-loop shape getFrequentlyBoughtTogether() already uses
    // for this catalog's scale - a product whose lookup fails (removed from the catalog, ProductService briefly
    // unreachable) is skipped rather than failing the whole batch, same reasoning as getPriceDropAlerts().
    public List<ProductRatingSummary> getRatingSummaries(List<Integer> productIds) {
        return productIds.stream()
                .map(id -> {
                    try {
                        return productClient.getRatingSummary(id);
                    } catch (FeignException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static final int DEFAULT_REVIEWS_PAGE_SIZE = 20;

    // Straight proxy to ProductService's own public review listing/posting - shop.html only ever calls its own
    // origin (see searchProducts()/getRatingSummaries() above for the same reasoning), so OrderService fronts it.
    // Each review is annotated with verifiedPurchase (see StorefrontReview) and stripped of the reviewer's phone.
    public List<StorefrontReview> getProductReviews(long productId, Integer page, Integer size) {
        int effectivePage = (page == null || page < 0) ? 0 : page;
        int effectiveSize = (size == null || size <= 0) ? DEFAULT_REVIEWS_PAGE_SIZE : size;
        Map<Long, Boolean> verifiedByPhno = new HashMap<>();
        return productClient.getReviews(serviceApiKey, productId, effectivePage, effectiveSize).content().stream()
                .map(r -> toStorefrontReview(r, productId,
                        verifiedByPhno.computeIfAbsent(r.reviewerPhno(), phno -> hasKeptPurchase(phno, productId))))
                .toList();
    }

    public StorefrontReview addProductReview(long productId, String reviewerName, long reviewerPhno, int rating, String comment) {
        ProductReview saved = productClient.addReview(productId, new ReviewSubmission(reviewerName, reviewerPhno, rating, comment));
        return toStorefrontReview(saved, productId, hasKeptPurchase(saved.reviewerPhno(), productId));
    }

    private static StorefrontReview toStorefrontReview(ProductReview r, long productId, boolean verified) {
        return new StorefrontReview(r.reviewId(), r.reviewerName(), r.rating(), r.comment(), r.createdAt(), verified);
    }

    // "Kept" = an order that was actually placed and not later cancelled; a returned order still counts (they did
    // buy and use it). PENDING_PAYMENT never completed, so it doesn't.
    private boolean hasKeptPurchase(long phno, long productId) {
        return orderRepository.findBycustomerPhno(phno).stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.PENDING_PAYMENT)
                .filter(o -> o.getOrderItems() != null)
                .anyMatch(o -> o.getOrderItems().stream().anyMatch(i -> i.getProductId() == productId));
    }

    // ---------- review moderation ----------

    // A signed-in customer reporting a review. The reason is optional and trimmed/capped so a report can't carry a
    // novel.
    public void flagReview(long productId, long reviewId, String reason) {
        String trimmed = reason == null || reason.isBlank() ? null : reason.trim();
        if (trimmed != null && trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200);
        }
        productClient.flagReview(productId, reviewId, trimmed);
    }

    // The admin queue: flagged reviews that haven't been hidden yet, straight from ProductService.
    public List<ModerationReview> getFlaggedReviews(Integer page, Integer size) {
        int effectivePage = page == null || page < 0 ? 0 : page;
        int effectiveSize = size == null || size <= 0 ? 50 : Math.min(size, 200);
        return productClient.getFlaggedReviews(serviceApiKey, effectivePage, effectiveSize).content();
    }

    public ModerationReview hideReview(long productId, long reviewId) {
        return productClient.hideReview(serviceApiKey, productId, reviewId);
    }

    public ModerationReview unhideReview(long productId, long reviewId) {
        return productClient.unhideReview(serviceApiKey, productId, reviewId);
    }

    // Straight proxy to ProductService's own public gallery listing - same "shop.html only calls its own
    // origin" reasoning as getProductReviews() above.
    public List<ProductGalleryImage> getGalleryImages(long productId) {
        return productClient.getGalleryImages(productId).stream()
                .map(i -> new ProductGalleryImage(i.id(), i.productId(), absolutizeImageUrl(i.imageUrl())))
                .toList();
    }

    private static final int DEFAULT_FREQUENTLY_BOUGHT_TOGETHER_LIMIT = 5;
    private static final int MAX_FREQUENTLY_BOUGHT_TOGETHER_LIMIT = 20;

    // Ranks other products by how many times they've actually appeared in the same order as productId - unlike
    // ProductService's same-category "related products" (which has no purchase history to draw on, only a
    // category guess), this is a real signal since OrderService owns the order data. CANCELLED orders are
    // excluded (never an actually-kept purchase); RETURNED ones still count (the pairing was genuinely bought
    // together, even if later sent back). A product that's since been removed from the catalog is skipped
    // rather than blowing up the whole list, same reasoning getPriceDropAlerts() already applies.
    public List<FrequentlyBoughtTogether> getFrequentlyBoughtTogether(int productId, Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0)
                ? DEFAULT_FREQUENTLY_BOUGHT_TOGETHER_LIMIT
                : Math.min(limit, MAX_FREQUENTLY_BOUGHT_TOGETHER_LIMIT);

        Map<Integer, Integer> coOccurrence = new HashMap<>();
        for (Cart cart : orderRepository.findAll()) {
            if (cart.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            List<OrderItem> items = cart.getOrderItems();
            if (items == null || items.stream().noneMatch(item -> item.getProductId() == productId)) {
                continue;
            }
            for (OrderItem item : items) {
                if (item.getProductId() == productId) {
                    continue;
                }
                coOccurrence.merge(item.getProductId(), 1, Integer::sum);
            }
        }

        return coOccurrence.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(effectiveLimit)
                .map(entry -> {
                    Product product;
                    try {
                        product = productClient.getProductById(entry.getKey());
                    } catch (FeignException e) {
                        return null;
                    }
                    return product == null ? null
                            : new FrequentlyBoughtTogether(product.getProductId(), product.getProductName(), entry.getValue());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static final int TOP_PRODUCTS_LIMIT = 5;

    // Admin-only sales rollup, same in-memory-aggregation-over-findAll() shape getFrequentlyBoughtTogether()
    // already uses at this system's scale - no separate reporting/warehouse store exists to query instead.
    // CANCELLED orders are excluded from every figure (fully refunded, never a kept sale); RETURNED ones still
    // count as revenue (no separate "returns" bucket exists yet to net them back out of the total).
    static final int MAX_TIMESERIES_DAYS = 366;

    // Revenue over time for the admin dashboard. An order belongs to the period it was placed in (its first
    // tracking event), in the caller's time zone so "a day" matches the dashboard user's own day. Cancelled and
    // still-unpaid UPI orders don't count; revenue is net of refunds, same as getSalesAnalytics().
    public RevenueTimeseries getRevenueTimeseries(LocalDate from, LocalDate to, String bucket, String zone) {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneOffset.UTC : ZoneId.of(zone.trim());
        } catch (java.time.DateTimeException e) {
            throw new ProductException("Unknown time zone: " + zone);
        }
        String unit = bucket == null || bucket.isBlank() ? "day" : bucket.trim().toLowerCase();
        if (!unit.equals("day") && !unit.equals("week")) {
            throw new ProductException("bucket must be day or week");
        }
        LocalDate end = to != null ? to : LocalDate.now(zoneId);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new ProductException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_TIMESERIES_DAYS) {
            throw new ProductException("The range can be at most " + MAX_TIMESERIES_DAYS + " days");
        }

        Map<Long, Instant> placedAtByOrder = new HashMap<>();
        for (TrackingEvent event : trackingEventRepository.findAll()) {
            placedAtByOrder.merge(event.getOrderId(), event.getTimestamp(), (a, b) -> a.isBefore(b) ? a : b);
        }

        boolean weekly = unit.equals("week");
        LocalDate firstPeriod = weekly ? start.with(java.time.DayOfWeek.MONDAY) : start;
        Map<LocalDate, long[]> orders = new java.util.LinkedHashMap<>();
        Map<LocalDate, Double> revenue = new HashMap<>();
        for (LocalDate p = firstPeriod; !p.isAfter(end); p = weekly ? p.plusWeeks(1) : p.plusDays(1)) {
            orders.put(p, new long[1]);
            revenue.put(p, 0.0);
        }

        long undated = 0;
        for (Cart order : orderRepository.findAll()) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                continue;
            }
            Instant placedAt = placedAtByOrder.get(order.getOrderId());
            if (placedAt == null) {
                undated++;
                continue;
            }
            LocalDate day = placedAt.atZone(zoneId).toLocalDate();
            if (day.isBefore(start) || day.isAfter(end)) {
                continue;
            }
            LocalDate period = weekly ? day.with(java.time.DayOfWeek.MONDAY) : day;
            orders.get(period)[0]++;
            revenue.merge(period, netPaid(order), Double::sum);
        }

        List<RevenueTimeseries.Point> points = new ArrayList<>();
        double totalRevenue = 0;
        long totalOrders = 0;
        for (Map.Entry<LocalDate, long[]> entry : orders.entrySet()) {
            double periodRevenue = roundMoney(revenue.get(entry.getKey()));
            points.add(new RevenueTimeseries.Point(entry.getKey(), entry.getValue()[0], periodRevenue));
            totalRevenue += periodRevenue;
            totalOrders += entry.getValue()[0];
        }
        return new RevenueTimeseries(start, end, unit, zoneId.getId(), points, roundMoney(totalRevenue), totalOrders, undated);
    }

    public SalesAnalytics getSalesAnalytics() {
        List<Cart> orders = orderRepository.findAll();

        long totalOrders = orders.size();
        // A handful of pre-existing dev-database rows predate the status/paymentMethod columns and can come back
        // null from a real query even though the entity's Java-side default never lets a freshly-built Cart have
        // one - grouped under "UNKNOWN" rather than throwing, same defensive spirit as skipping a product whose
        // catalog lookup fails elsewhere in this class.
        Map<String, Long> ordersByStatus = orders.stream()
                .collect(Collectors.groupingBy(cart -> statusNameOrUnknown(cart.getStatus()), Collectors.counting()));

        List<Cart> countedOrders = orders.stream()
                .filter(cart -> cart.getStatus() != OrderStatus.CANCELLED)
                .toList();

        // Net of refunds: a returned order (or returned/cancelled items) is no longer revenue.
        double totalRevenue = countedOrders.stream().mapToDouble(OrderService::netPaid).sum();
        Map<String, Double> revenueByPaymentMethod = countedOrders.stream()
                .collect(Collectors.groupingBy(cart -> paymentMethodNameOrUnknown(cart.getPaymentMethod()),
                        Collectors.summingDouble(OrderService::netPaid)));

        Map<Integer, Integer> unitsSoldByProduct = new HashMap<>();
        Map<Integer, Double> revenueByProduct = new HashMap<>();
        for (Cart cart : countedOrders) {
            List<OrderItem> items = cart.getOrderItems();
            if (items == null) continue;
            if (cart.getStatus() == OrderStatus.RETURNED || cart.getStatus() == OrderStatus.PENDING_PAYMENT) continue;
            for (OrderItem item : items) {
                int kept = item.getOutstandingQuantity();
                if (kept <= 0) continue;
                unitsSoldByProduct.merge(item.getProductId(), kept, Integer::sum);
                revenueByProduct.merge(item.getProductId(),
                        kept * productPriceOrZero(item.getProductId()), Double::sum);
            }
        }

        List<TopSellingProduct> topProducts = unitsSoldByProduct.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(TOP_PRODUCTS_LIMIT)
                .map(entry -> {
                    Product product;
                    try {
                        product = productClient.getProductById(entry.getKey());
                    } catch (FeignException e) {
                        return null;
                    }
                    return product == null ? null
                            : new TopSellingProduct(product.getProductId(), product.getProductName(),
                                    entry.getValue(), revenueByProduct.getOrDefault(entry.getKey(), 0.0));
                })
                .filter(Objects::nonNull)
                .toList();

        return new SalesAnalytics(totalOrders, totalRevenue, ordersByStatus, revenueByPaymentMethod, topProducts);
    }

    private static String statusNameOrUnknown(OrderStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }

    private static String paymentMethodNameOrUnknown(PaymentMethod paymentMethod) {
        return paymentMethod == null ? "UNKNOWN" : paymentMethod.name();
    }

    // A product's current price, cached implicitly by the caller's own map - used only to turn units sold into
    // an approximate revenue-per-product figure (the order's actual totalPrice already reflects coupon/points
    // discounts at the whole-order level, which aren't split back out per line item anywhere in this system).
    private double productPriceOrZero(int productId) {
        try {
            Product product = productClient.getProductById(productId);
            return product == null ? 0.0 : product.getProductPrice();
        } catch (FeignException e) {
            return 0.0;
        }
    }

    @Transactional
    public List<Cart> deleteProduct(long phno, long productId) {
        validatePhno(phno);

        List<Cart> carts = orderRepository.findBycustomerPhno(phno);

        for (Cart cart : carts) {
            // Only an order that hasn't shipped can still lose a line: cancelled/returned ones already put their
            // stock back (restocking again would inflate it), shipped/delivered ones are history.
            if (cart.getStatus() != OrderStatus.PLACED) {
                continue;
            }
            List<OrderItem> orderItems = cart.getOrderItems();
            double price = cart.getTotalPrice();
            for(int i=0;i<orderItems.size();i++)
            {
                if(orderItems.get(i).getProductId()==productId)
                {
                    long t=orderItems.get(i).getId();
                    Product pro=productClient.getProductById(orderItems.get(i).getProductId());
                    price=price-(orderItems.get(i).getProductQuantity()*pro.getProductPrice());
                    productClient.updateProductStock(serviceApiKey, orderItems.get(i).getProductId(),+orderItems.get(i).getProductQuantity());
                    orderItems.remove(i);
                    orderItemRepository.deleteById(t);
                    i--;
                }
            }

            cart.setTotalPrice(price);
            orderRepository.save(cart);
        }

        return carts;
    }

    // Shared by every endpoint that identifies a customer by phone number alone (order, ordersOfPhno,
    // deleteProduct, and the wishlist methods below) - this system has no login, so a valid Indian mobile number
    // is the only identity check there is.
    private void validatePhno(long phno) {
        String x = "" + phno;
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }

    // Idempotent by design: adding a product that's already on the wishlist returns the existing entry rather
    // than erroring or creating a duplicate row - the caller just wants it on the list, not to know whether it
    // was already there.
    public Wishlist addToWishlist(long phno, int productId) {
        validatePhno(phno);
        // ProductService's real /product/byId throws (never returns null) for a missing id, unlike what a
        // mocked ProductClient in a unit test might suggest - any Feign failure here means the product doesn't
        // exist as far as this caller is concerned.
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product not found");
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        return wishlistRepository.findByCustomerPhnoAndProductId(phno, productId)
                .orElseGet(() -> {
                    Wishlist wishlist = new Wishlist();
                    wishlist.setCustomerPhno(phno);
                    wishlist.setProductId(productId);
                    // Snapshot the price at add time - getPriceDropAlerts() compares it against the product's
                    // current price to detect a drop.
                    wishlist.setPriceWhenAdded(product.getProductPrice());
                    return wishlistRepository.save(wishlist);
                });
    }

    public List<Wishlist> getWishlist(long phno) {
        validatePhno(phno);
        return wishlistRepository.findByCustomerPhno(phno);
    }

    // Computed on demand rather than pushed anywhere - this system has no scheduler to watch prices with, so
    // "alert" here means "ask and find out right now", not a
    // proactive notification. Re-fetches each product's CURRENT price fresh on every call, so it's always
    // accurate even though nothing is persisted between calls. An entry with no priceWhenAdded (wishlisted
    // before this field existed) or whose product Feign lookup fails is skipped rather than reported.
    public List<WishlistPriceAlert> getPriceDropAlerts(long phno) {
        validatePhno(phno);
        List<WishlistPriceAlert> alerts = new ArrayList<>();
        for (Wishlist item : wishlistRepository.findByCustomerPhno(phno)) {
            if (item.getPriceWhenAdded() == null) {
                continue;
            }
            Product product;
            try {
                product = productClient.getProductById(item.getProductId());
            } catch (FeignException e) {
                continue;
            }
            if (product == null || product.getProductPrice() >= item.getPriceWhenAdded()) {
                continue;
            }
            double drop = item.getPriceWhenAdded() - product.getProductPrice();
            alerts.add(new WishlistPriceAlert(product.getProductId(), product.getProductName(),
                    item.getPriceWhenAdded(), product.getProductPrice(), drop));
        }
        return alerts;
    }

    @Transactional
    public void removeFromWishlist(long phno, int productId) {
        validatePhno(phno);
        wishlistRepository.deleteByCustomerPhnoAndProductId(phno, productId);
    }

    // ---------- back-in-stock waitlist ----------

    // Idempotent by design, same reasoning as addToWishlist() - the caller just wants to be on the list, not to
    // know whether they already were. Deliberately does NOT require the product to actually be out of stock
    // right now - a customer waitlisting a moment before it sells out (or just being cautious) shouldn't 404.
    public StockWaitlist addToWaitlist(long phno, int productId) {
        validatePhno(phno);
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product not found");
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        return stockWaitlistRepository.findByCustomerPhnoAndProductId(phno, productId)
                .orElseGet(() -> {
                    StockWaitlist waitlist = new StockWaitlist();
                    waitlist.setCustomerPhno(phno);
                    waitlist.setProductId(productId);
                    return stockWaitlistRepository.save(waitlist);
                });
    }

    // Admin orders table / CSV source: every order, newest first, optionally narrowed by status, payment method,
    // customer phone and a placed-on date range (inclusive, UTC days - an order's date is its first tracking
    // event). Orders with no tracking event (placed before tracking existed) have no date, so any date filter
    // excludes them.
    public List<AdminOrderRow> searchOrders(String status, String paymentMethod, Long phno,
                                            java.time.LocalDate from, java.time.LocalDate to) {
        OrderStatus wantedStatus = parseEnumFilter(OrderStatus.class, status, "status");
        PaymentMethod wantedMethod = parseEnumFilter(PaymentMethod.class, paymentMethod, "paymentMethod");
        Map<Long, Instant> placedAtByOrder = new HashMap<>();
        for (TrackingEvent event : trackingEventRepository.findAll()) {
            placedAtByOrder.merge(event.getOrderId(), event.getTimestamp(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        Instant fromInstant = from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        List<AdminOrderRow> rows = new ArrayList<>();
        for (Cart order : orderRepository.findAll()) {
            if (wantedStatus != null && order.getStatus() != wantedStatus) continue;
            if (wantedMethod != null && order.getPaymentMethod() != wantedMethod) continue;
            if (phno != null && order.getCustomerPhno() != phno) continue;
            Instant placedAt = placedAtByOrder.get(order.getOrderId());
            if (fromInstant != null && (placedAt == null || placedAt.isBefore(fromInstant))) continue;
            if (toExclusive != null && (placedAt == null || !placedAt.isBefore(toExclusive))) continue;
            String items = order.getOrderItems() == null ? "" : order.getOrderItems().stream()
                    .map(i -> i.getProductId() + " x " + i.getProductQuantity())
                    .collect(Collectors.joining("; "));
            rows.add(new AdminOrderRow(order.getOrderId(), placedAt, order.getCustomerName(),
                    order.getCustomerPhno(), String.valueOf(order.getStatus()),
                    String.valueOf(order.getPaymentMethod()), order.isPaid(), items, order.getCouponCode(),
                    order.getDiscountAmount(), order.getPointsRedeemed() == null ? 0 : order.getPointsRedeemed(),
                    order.getTotalPrice(), order.getDeliveryNote(), order.getDeliverySlot()));
        }
        rows.sort(Comparator.comparingLong(AdminOrderRow::orderId).reversed());
        return rows;
    }

    // Same filters as searchOrders(), rendered as CSV (RFC 4180 quoting). Free-text cells starting with =, +, -
    // or @ are prefixed with an apostrophe so a hostile customer name can't run as a spreadsheet formula.
    public String exportOrdersCsv(String status, String paymentMethod, Long phno,
                                  java.time.LocalDate from, java.time.LocalDate to) {
        StringBuilder csv = new StringBuilder(
                "orderId,placedAt,customerName,customerPhno,status,paymentMethod,paid,items,couponCode,"
                        + "discountAmount,pointsRedeemed,totalPrice,deliveryNote,deliverySlot\r\n");
        for (AdminOrderRow r : searchOrders(status, paymentMethod, phno, from, to)) {
            csv.append(r.orderId()).append(',')
                    .append(r.placedAt() == null ? "" : r.placedAt()).append(',')
                    .append(csvCell(r.customerName())).append(',')
                    .append(r.customerPhno()).append(',')
                    .append(r.status()).append(',')
                    .append(r.paymentMethod()).append(',')
                    .append(r.paid()).append(',')
                    .append(csvCell(r.items())).append(',')
                    .append(csvCell(r.couponCode())).append(',')
                    .append(r.discountAmount()).append(',')
                    .append(r.pointsRedeemed()).append(',')
                    .append(r.totalPrice()).append(',')
                    .append(csvCell(r.deliveryNote())).append(",").append(csvCell(r.deliverySlot())).append("\r\n");
        }
        return csv.toString();
    }

    static String csvCell(String value) {
        if (value == null) return "";
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnumFilter(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProductException("Invalid " + name + " filter");
        }
    }

    // Admin-only restock report: every catalog product whose stock is at or below its own lowStockThreshold, with
    // OUT (stock 0) listed before LOW, lowest stock first, plus how many customers are waiting on it. Computed live
    // from the catalog on each call (no scheduler/push provider exists here).
    public List<LowStockItem> getLowStockReport() {
        List<LowStockItem> items = new ArrayList<>();
        for (Product p : productClient.findAll()) {
            if (p.getProductStock() <= p.getLowStockThreshold()) {
                items.add(new LowStockItem(p.getProductId(), p.getProductName(), p.getProductStock(),
                        p.getLowStockThreshold(), p.getProductStock() <= 0 ? "OUT" : "LOW",
                        stockWaitlistRepository.countByProductId(p.getProductId())));
            }
        }
        items.sort(Comparator.comparingInt(LowStockItem::productStock).thenComparingInt(LowStockItem::productId));
        return items;
    }

    // Computed on demand, same "no scheduler/push provider exists here" reasoning as getPriceDropAlerts() - live
    // stock is re-fetched fresh on every call rather than tracked/pushed. A product whose Feign lookup fails
    // (removed from the catalog) is skipped rather than failing the whole list.
    public List<WaitlistStatus> getWaitlist(long phno) {
        validatePhno(phno);
        List<WaitlistStatus> statuses = new ArrayList<>();
        for (StockWaitlist item : stockWaitlistRepository.findByCustomerPhno(phno)) {
            Product product;
            try {
                product = productClient.getProductById(item.getProductId());
            } catch (FeignException e) {
                continue;
            }
            if (product == null) {
                continue;
            }
            statuses.add(new WaitlistStatus(product.getProductId(), product.getProductName(),
                    product.getProductStock(), product.getProductStock() > 0));
        }
        return statuses;
    }

    @Transactional
    public void removeFromWaitlist(long phno, int productId) {
        validatePhno(phno);
        stockWaitlistRepository.deleteByCustomerPhnoAndProductId(phno, productId);
    }

    // At most one default address per customer: making this one the default silently un-defaults whichever one
    // previously held it, rather than requiring the caller to unset the old one themselves first.
    public ShippingAddress saveAddress(ShippingAddress address) {
        validatePhno(address.getCustomerPhno());
        if (address.getLine1() == null || address.getLine1().isBlank()
                || address.getCity() == null || address.getCity().isBlank()
                || address.getState() == null || address.getState().isBlank()
                || address.getPincode() == null || address.getPincode().isBlank()) {
            throw new ProductException("Address line 1, city, state and pincode are required");
        }
        // An id in the body makes save() update that row - only allowed for the caller's own address, otherwise
        // anyone could overwrite (and take over) another customer's saved address.
        if (address.getId() != null) {
            ShippingAddress current = shippingAddressRepository.findById(address.getId()).orElse(null);
            if (current == null || current.getCustomerPhno() != address.getCustomerPhno()) {
                throw new OrderNotFoundException("Address not found");
            }
        }
        if (address.isDefault()) {
            List<ShippingAddress> existingDefaults = shippingAddressRepository
                    .findByCustomerPhnoAndIsDefaultTrue(address.getCustomerPhno());
            for (ShippingAddress existing : existingDefaults) {
                existing.setDefault(false);
                shippingAddressRepository.save(existing);
            }
        }
        return shippingAddressRepository.save(address);
    }

    public List<ShippingAddress> getAddresses(long phno) {
        validatePhno(phno);
        return shippingAddressRepository.findByCustomerPhno(phno);
    }

    // Ownership-checked the same way validateShippingAddress() checks it at checkout: a phone number can only
    // ever remove its own saved addresses, never one it merely guessed the id of.
    public void deleteAddress(long phno, long addressId) {
        validatePhno(phno);
        ShippingAddress address = shippingAddressRepository.findById(addressId)
                .orElseThrow(() -> new OrderNotFoundException("Address not found"));
        if (address.getCustomerPhno() != phno) {
            throw new OrderNotFoundException("Address not found");
        }
        shippingAddressRepository.deleteById(addressId);
    }

    // A read-only rollup, not a new source of truth - each figure is fetched from wherever it's already owned
    // (Cart/Wishlist/LoyaltyAccount here, reviews via Feign to ProductService) rather than duplicated into a
    // new table. If ProductService can't be reached, the review count degrades to 0 rather than failing the
    // whole profile - same "skip, don't blow up" reasoning as getPriceDropAlerts()/getFrequentlyBoughtTogether().
    public CustomerProfile getCustomerProfile(long phno) {
        validatePhno(phno);
        int totalOrders = orderRepository.findBycustomerPhno(phno).size();
        int wishlistCount = wishlistRepository.findByCustomerPhno(phno).size();
        long reviewCount;
        try {
            reviewCount = productClient.getReviewCount(phno);
        } catch (FeignException e) {
            reviewCount = 0;
        }
        LoyaltyAccount loyaltyAccount = getLoyaltyAccount(phno);
        return new CustomerProfile(phno, totalOrders, wishlistCount, reviewCount,
                loyaltyAccount.getTier(), loyaltyAccount.getPointsBalance(), loyaltyAccount.getLifetimePointsEarned());
    }
}
