package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CouponRepository;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
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
import java.util.List;

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
    private WishlistRepository wishlistRepository;
    @Autowired
    private TrackingEventRepository trackingEventRepository;
    @Autowired
    private ShippingAddressRepository shippingAddressRepository;
    @Autowired
    ProductClient productClient;
    @Autowired
    PhonepeClient phonepeClient;
    @Autowired
    private OrderKafkaProducer orderKafkaProducer;
    @Value("${internal.service.api-key}")
    private String serviceApiKey;
    public Cart order(Cart cart, String authorization, String idempotencyKey)
    {
        validatePhno(cart.getCustomerPhno());
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
        double finalPrice = price - discount;
        // Same fail-fast reasoning as stock/coupon above: an address that doesn't exist, or belongs to someone
        // else's phone number, must reject the order before any payment is attempted.
        validateShippingAddress(cart);
        // Charge the buyer BEFORE creating the order or touching stock: if PhonepayService refuses the payment
        // (insufficient funds, expired session, locked account, bank down, ...) nothing here should exist either.
        PaymentResponse payment = charge(authorization, finalPrice, idempotencyKey);
        cart.setTotalPrice(finalPrice);
        cart.setDiscountAmount(discount);
        cart.setStatus(OrderStatus.PLACED);
        cart.setPaymentTransactionId(payment.transactionId());
        Cart saved=orderRepository.save(cart);
        for(OrderItem orderItem : saved.getOrderItems())
        {
            orderItem.setOrderId(saved.getOrderId());
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(),-orderItem.getProductQuantity());
        }
        Cart result = orderRepository.save(saved);
        recordTracking(result.getOrderId(), OrderStatus.PLACED);
        sendNotification("Order placed successfully. OrderId: " + result.getOrderId()
                + " Customer: " + mask(result.getCustomerPhno())
                + " Items: " + result.getOrderItems().size()
                + " Total: " + result.getTotalPrice());
        return result;
    }

    // Cancels an order that hasn't already been cancelled: refunds the buyer's own payment in full, and only on
    // a successful refund restores stock and marks the order CANCELLED - a declined/failed refund leaves the
    // order exactly as it was, the same fail-safe shape order() already uses for placing one.
    public Cart cancel(long orderId, String authorization, String idempotencyKey) {
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() == OrderStatus.CANCELLED) {
            throw new ProductException("This order is already cancelled");
        }
        if (cart.getStatus() != OrderStatus.PLACED) {
            throw new ProductException("Only a placed order can be cancelled");
        }
        if (cart.getPaymentTransactionId() == null) {
            throw new ProductException("This order cannot be cancelled");
        }

        refund(authorization, cart.getPaymentTransactionId(), idempotencyKey);

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
        if (reason == null || reason.isBlank()) {
            throw new ProductException("A return reason is required");
        }
        Cart cart = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (cart.getStatus() != OrderStatus.DELIVERED) {
            throw new ProductException("Only a delivered order can be returned");
        }
        if (cart.getPaymentTransactionId() == null) {
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

        refund(authorization, cart.getPaymentTransactionId(), idempotencyKey);

        for (OrderItem orderItem : cart.getOrderItems()) {
            productClient.updateProductStock(serviceApiKey, orderItem.getProductId(), orderItem.getProductQuantity());
        }
        cart.setStatus(OrderStatus.RETURNED);
        cart.setReturnReason(reason);
        Cart result = orderRepository.save(cart);
        recordTracking(result.getOrderId(), OrderStatus.RETURNED);
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
        cart.setCouponCode(normalized);
        return price * coupon.getDiscountPercent() / 100.0;
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
        coupon.setCode(coupon.getCode().trim().toUpperCase());
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
        sendNotification("Order delivered. OrderId: " + result.getOrderId());
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
                    return wishlistRepository.save(wishlist);
                });
    }

    public List<Wishlist> getWishlist(long phno) {
        validatePhno(phno);
        return wishlistRepository.findByCustomerPhno(phno);
    }

    @Transactional
    public void removeFromWishlist(long phno, int productId) {
        validatePhno(phno);
        wishlistRepository.deleteByCustomerPhnoAndProductId(phno, productId);
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
}
