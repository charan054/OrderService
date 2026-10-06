package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.CreateUpiCollectRequest;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.PhonepeForgotPinRequest;
import com.example.orderservice.dto.PhonepeLoginRequest;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.dto.PhonepeResetPinRequest;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.dto.SalesAnalytics;
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
import com.example.orderservice.entity.ShippingAddress;
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
    private NotificationLogRepository notificationLogRepository;
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
        return result;
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

    // Resolves a PENDING_PAYMENT order the moment anyone next asks about it (the storefront polling this while
    // the buyer goes to approve in PhonepayService) - this system has no scheduler, same reasoning as
    // OrderService's own loyalty-points-expiry. OrderService's OWN deadline is checked FIRST and is
    // authoritative regardless of what PhonepayService's own collect-request expiry says (the two are set to
    // the same duration, but this keeps OrderService in control of how long an order actually holds its
    // reserved stock even if that ever changes independently on PhonepayService's side).
    public Cart checkPendingPayment(long orderId) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return cart;
        }
        if (Instant.now().isAfter(cart.getPaymentDeadline())) {
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
        return result;
    }

    // No refund call here (unlike cancel()) - a PENDING_PAYMENT order was never actually charged, so there is
    // nothing PhonepayService needs to reverse.
    private Cart cancelUnpaidOrder(Cart cart, String reason) {
        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
        }
        cart.setStatus(OrderStatus.CANCELLED);
        cart.setPaymentDeadline(null);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.CANCELLED);
        sendNotification("Order cancelled successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Reason: " + reason);
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
            refund(token, cart.getPaymentTransactionId(), idempotencyKey);
        }

        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
        }
        cart.setStatus(OrderStatus.CANCELLED);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.CANCELLED);
        sendNotification("Order cancelled successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Refunded: " + result.getTotalPrice());
        return result;
    }

    // Returns can only happen AFTER delivery, unlike cancel() which only works on a still-PLACED order - the two
    // are mutually exclusive by status, never overlapping windows. Otherwise the same fail-safe refund-then-
    // restore-stock shape as cancel(): a declined refund leaves the order exactly DELIVERED, nothing rolled back.
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

        List<TrackingEvent> deliveredEvents = trackingEventRepository.findByOrderIdOrderByTimestampAsc(orderId);
        Instant deliveredAt = deliveredEvents.stream()
                .filter(e -> e.getStatus() == OrderStatus.DELIVERED)
                .map(TrackingEvent::getTimestamp)
                .reduce((first, second) -> second) // latest DELIVERED event, in case of any anomaly
                .orElse(null);
        if (deliveredAt != null && deliveredAt.isBefore(Instant.now().minus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new ProductException("Return window of " + RETURN_WINDOW_DAYS + " days has expired");
        }

        if (cart.getPaymentMethod() != PaymentMethod.CASH) {
            String token = resolveBuyerToken(authorization, payerPhno, payerPin);
            refund(token, cart.getPaymentTransactionId(), idempotencyKey);
        }

        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
        }
        cart.setStatus(OrderStatus.RETURNED);
        cart.setReturnReason(reason);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.RETURNED);
        clawBackLoyaltyPoints(result);
        sendNotification("Order returned successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Reason: " + reason
                + " Refunded: " + result.getTotalPrice());
        return result;
    }

    // No code, no discount - the common case. A code that doesn't match any Coupon, or matches one that's been
    // deactivated, must fail loudly rather than silently charging full price (a buyer trusting a "10% off"
    // banner should never find out only after being charged in full).
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
        int baseEarned = (int) (cart.getTotalPrice() / RUPEES_PER_POINT);
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
    private void clawBackLoyaltyPoints(Cart cart) {
        LoyaltyTransaction earnedTx = loyaltyTransactionRepository
                .findByOrderIdAndType(cart.getOrderId(), LoyaltyTransactionType.EARNED)
                .orElse(null);
        if (earnedTx == null || earnedTx.getPoints() <= 0) {
            return;
        }
        int earned = earnedTx.getPoints();
        LoyaltyAccount account = loadLoyaltyAccount(cart.getCustomerPhno());
        int clawedBack = Math.min(earned, account.getPointsBalance());
        if (clawedBack <= 0) {
            return;
        }
        account.setPointsBalance(account.getPointsBalance() - clawedBack);
        account.setLifetimePointsEarned(Math.max(0, account.getLifetimePointsEarned() - clawedBack));
        account.setLastActivityAt(Instant.now());
        loyaltyAccountRepository.save(account);
        recordLoyaltyTransaction(cart.getCustomerPhno(), cart.getOrderId(), -clawedBack, LoyaltyTransactionType.ADJUSTED,
                "Points earned on order #" + cart.getOrderId() + " reversed after return");
    }

    private LoyaltyAccount newLoyaltyAccount(long customerPhno) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setCustomerPhno(customerPhno);
        account.setPointsBalance(0);
        return account;
    }

    // ~12 months - Instant has no calendar-month arithmetic (ChronoUnit.MONTHS isn't a supported unit for it,
    // unlike LocalDate), so this is expressed in days instead.
    private static final int POINTS_EXPIRY_DAYS = 365;

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

    public List<Coupon> getCoupons() {
        return couponRepository.findAll();
    }

    // Ship/deliver form a strict one-way lifecycle on top of PLACED/CANCELLED: PLACED -> SHIPPED -> DELIVERED.
    // Neither step touches stock or payment - those were already settled at order() time - so there's nothing
    // to roll back if a later step never happens.
    public Cart ship(long orderId) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Only a placed order can be shipped");
        }
        cart.setStatus(OrderStatus.SHIPPED);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.SHIPPED);
        sendNotification("Order shipped. OrderId: " + result.getOrderId());
        return result;
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
        sendNotification("Order delivered. OrderId: " + result.getOrderId());
        return result;
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

    public List<TrackingEvent> getTracking(long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException("Order not found");
        }
        return trackingEventRepository.findByOrderIdOrderByTimestampAsc(orderId);
    }

    // The audit trail OrderKafkaConsumer writes to for SHIPPED/DELIVERED events - see NotificationLog for why
    // this is "dispatched" rather than actually emailed/texted anywhere yet.
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

    private void refund(String authorization, long paymentTransactionId, String idempotencyKey) {
        PaymentResponse refund;
        try {
            refund = phonepeClient.refund(authorization, paymentTransactionId, new RefundRequest(idempotencyKey));
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
    public List<Product> getProducts()
    {
        return productClient.findAll();
    }

    // MAX_SEARCH_RESULTS caps the single page requested from ProductService's own paginated /product/search -
    // this storefront's catalog is small enough that a single generously-sized page is simpler than exposing
    // pagination end-to-end through OrderService too.
    private static final int MAX_SEARCH_RESULTS = 200;

    public List<Product> searchProducts(String name, String category) {
        return productClient.search(blankToNull(name), blankToNull(category), MAX_SEARCH_RESULTS).content();
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
    public List<ProductReview> getProductReviews(long productId, Integer page, Integer size) {
        int effectivePage = (page == null || page < 0) ? 0 : page;
        int effectiveSize = (size == null || size <= 0) ? DEFAULT_REVIEWS_PAGE_SIZE : size;
        return productClient.getReviews(productId, effectivePage, effectiveSize).content();
    }

    public ProductReview addProductReview(long productId, String reviewerName, long reviewerPhno, int rating, String comment) {
        return productClient.addReview(productId, new ReviewSubmission(reviewerName, reviewerPhno, rating, comment));
    }

    // Straight proxy to ProductService's own public gallery listing - same "shop.html only calls its own
    // origin" reasoning as getProductReviews() above.
    public List<ProductGalleryImage> getGalleryImages(long productId) {
        return productClient.getGalleryImages(productId);
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

        double totalRevenue = countedOrders.stream().mapToDouble(Cart::getTotalPrice).sum();
        Map<String, Double> revenueByPaymentMethod = countedOrders.stream()
                .collect(Collectors.groupingBy(cart -> paymentMethodNameOrUnknown(cart.getPaymentMethod()),
                        Collectors.summingDouble(Cart::getTotalPrice)));

        Map<Integer, Integer> unitsSoldByProduct = new HashMap<>();
        Map<Integer, Double> revenueByProduct = new HashMap<>();
        for (Cart cart : countedOrders) {
            List<OrderItem> items = cart.getOrderItems();
            if (items == null) continue;
            for (OrderItem item : items) {
                unitsSoldByProduct.merge(item.getProductId(), item.getProductQuantity(), Integer::sum);
                revenueByProduct.merge(item.getProductId(),
                        item.getProductQuantity() * productPriceOrZero(item.getProductId()), Double::sum);
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

    // Computed on demand rather than pushed anywhere - this system has no scheduler and no email/SMS provider
    // (same caveat NotificationLog already carries), so "alert" here means "ask and find out right now", not a
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
