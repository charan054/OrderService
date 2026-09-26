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
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CouponRepository;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import com.example.orderservice.repository.WishlistRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String SERVICE_KEY = "test-service-key";
    private static final String AUTH = "Bearer buyer-token";
    private static final long CUSTOMER = 9876543210L;

    @Mock
    private CartRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private WishlistRepository wishlistRepository;
    @Mock
    private TrackingEventRepository trackingEventRepository;
    @Mock
    private ProductClient productClient;
    @Mock
    private PhonepeClient phonepeClient;
    @Mock
    private OrderKafkaProducer orderKafkaProducer;

    @InjectMocks
    private OrderService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "serviceApiKey", SERVICE_KEY);
    }

    // Simulates the database assigning the generated id on first save, the way JPA actually would.
    private void stubCartSaveAssignsAnId() {
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart cart = invocation.getArgument(0);
            if (cart.getOrderId() == null) {
                cart.setOrderId(42L);
            }
            return cart;
        });
    }

    private Product product(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("Widget " + id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    private OrderItem item(int productId, int quantity) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(quantity);
        return i;
    }

    private Cart cart(long phno, OrderItem... items) {
        Cart c = new Cart();
        c.setCustomerName("Buyer");
        c.setCustomerPhno(phno);
        c.setOrderItems(new ArrayList<>(List.of(items)));
        return c;
    }

    private Coupon coupon(String code, double discountPercent, boolean active) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountPercent(discountPercent);
        c.setActive(active);
        return c;
    }

    // ---------- order() ----------

    private FeignException declinedBy(String methodKey, int status, String message) {
        Request request = Request.create(Request.HttpMethod.POST, "/phonepe/" + methodKey,
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("declined")
                .request(request)
                .body(message, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus(methodKey, response);
    }

    private PaymentResponse paymentResponse(long transactionId) {
        return paymentResponse(transactionId, "COMPLETED");
    }

    private PaymentResponse paymentResponse(long transactionId, String status) {
        return new PaymentResponse(transactionId, "Payment", "DEBIT", CUSTOMER, null,
                new BigDecimal("1045.00"), status, Instant.parse("2026-09-25T10:00:00Z"), "Order payment");
    }

    @Test
    void orderRejectsAnInvalidPhoneNumber() {
        Cart cart = cart(12345, item(1, 1));
        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderKafkaProducer);
    }

    @Test
    void orderThrowsWhenAProductDoesNotExist() {
        when(productClient.getProductById(1)).thenReturn(null);
        Cart cart = cart(CUSTOMER, item(1, 1));
        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verify(orderRepository, never()).save(any());
    }

    // Regression: a later item failing must never leave an earlier item's stock decremented with no order saved.
    @Test
    void orderThrowsWhenQuantityExceedsStockAndTouchesNoStockOrOrder() {
        when(productClient.getProductById(1)).thenReturn(product(1, 45.0, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 10.0, 1));
        Cart cart = cart(CUSTOMER, item(1, 1), item(2, 99));

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    // A declined payment (insufficient funds, expired session, ...) must leave no order and no stock touched -
    // the whole point of charging BEFORE saving the cart or decrementing stock.
    @Test
    void orderThrowsWhenPaymentIsDeclinedAndTouchesNoStockOrOrder() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class)))
                .thenThrow(declinedBy("makepayment", 400, "Insufficient Funds"));
        Cart cart = cart(CUSTOMER, item(1, 1));

        PaymentException ex = assertThrows(PaymentException.class, () -> service.order(cart, AUTH, null));

        assertEquals("Insufficient Funds", ex.getMessage());
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderChargesTheBuyersOwnTokenForTheComputedTotalBeforeSavingOrDecrementingStock() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(productClient.getProductById(2)).thenReturn(product(2, 45.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 2), item(2, 1));

        Cart result = service.order(cart, AUTH, "checkout-123");

        assertEquals(1045.0, result.getTotalPrice());
        assertEquals(42L, result.getOrderId());
        assertEquals(OrderStatus.PLACED, result.getStatus());
        assertEquals(100000L, result.getPaymentTransactionId());
        for (OrderItem oi : result.getOrderItems()) {
            assertEquals(42L, oi.getOrderId());
        }
        verify(phonepeClient).makePayment(AUTH, new PaymentRequest(new BigDecimal("1045.00"), "Order payment", "checkout-123"));
        verify(productClient).updateProductStock(SERVICE_KEY, 1, -2);
        verify(productClient).updateProductStock(SERVICE_KEY, 2, -1);
        verify(orderRepository, times(2)).save(any());
        verify(orderKafkaProducer).sendMessage(contains("Order placed successfully"));
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.PLACED, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderId());
    }

    @Test
    void orderNotificationMasksThePhoneNumber() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        service.order(cart, AUTH, null);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(orderKafkaProducer).sendMessage(message.capture());
        assertTrue(message.getValue().contains("XXXXXX3210"));
        assertFalse(message.getValue().contains(String.valueOf(CUSTOMER)));
    }

    // A broken notification channel must never turn a completed order into an error.
    @Test
    void orderKafkaFailureDoesNotFailTheOrder() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        doThrow(new RuntimeException("kafka down")).when(orderKafkaProducer).sendMessage(any());
        Cart cart = cart(CUSTOMER, item(1, 1));

        Cart result = service.order(cart, AUTH, null);

        assertEquals(OrderStatus.PLACED, result.getStatus());
    }

    // Regression: an Idempotency-Key retry can hand back an existing transaction whose status is
    // NEEDS_RECONCILIATION (or anything but COMPLETED) with no exception at all - a 200 response alone must
    // never be treated as proof the payment actually went through.
    @Test
    void orderRejectsAPaymentThatIsNotActuallyCompleted() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class)))
                .thenReturn(paymentResponse(100000, "NEEDS_RECONCILIATION"));
        Cart cart = cart(CUSTOMER, item(1, 1));

        assertThrows(PaymentException.class, () -> service.order(cart, AUTH, null));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    // ---------- coupons ----------

    @Test
    void orderAppliesAValidCouponBeforeChargingAndSavingTheDiscountedTotal() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(coupon("SAVE10", 10, true)));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("save10");

        Cart result = service.order(cart, AUTH, null);

        assertEquals(450.0, result.getTotalPrice());
        assertEquals(50.0, result.getDiscountAmount());
        assertEquals("SAVE10", result.getCouponCode());
        verify(phonepeClient).makePayment(eq(AUTH), eq(new PaymentRequest(new BigDecimal("450.00"), "Order payment", null)));
    }

    @Test
    void orderThrowsForAnUnknownCouponCodeAndTouchesNoPaymentOrStock() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(couponRepository.findById("BOGUS")).thenReturn(Optional.empty());
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("BOGUS");

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(phonepeClient);
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderThrowsForADeactivatedCoupon() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(couponRepository.findById("OLD10")).thenReturn(Optional.of(coupon("OLD10", 10, false)));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("OLD10");

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(phonepeClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderWithNoCouponCodeChargesFullPriceAndTouchesNoCouponLookup() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        Cart result = service.order(cart, AUTH, null);

        assertEquals(500.0, result.getTotalPrice());
        assertEquals(0.0, result.getDiscountAmount());
        verifyNoInteractions(couponRepository);
    }

    @Test
    void saveCouponRejectsABlankCode() {
        assertThrows(ProductException.class, () -> service.saveCoupon(coupon(" ", 10, true)));
        verify(couponRepository, never()).save(any());
    }

    @Test
    void saveCouponRejectsAnOutOfRangeDiscountPercent() {
        assertThrows(ProductException.class, () -> service.saveCoupon(coupon("BAD", 0, true)));
        assertThrows(ProductException.class, () -> service.saveCoupon(coupon("BAD", 101, true)));
        verify(couponRepository, never()).save(any());
    }

    @Test
    void saveCouponNormalizesTheCodeToUppercase() {
        Coupon input = coupon("save10", 10, true);
        when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        Coupon result = service.saveCoupon(input);

        assertEquals("SAVE10", result.getCode());
    }

    // ---------- wishlist ----------

    @Test
    void addToWishlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.addToWishlist(12345, 1));
        verifyNoInteractions(productClient, wishlistRepository);
    }

    @Test
    void addToWishlistThrowsWhenTheProductDoesNotExist() {
        when(productClient.getProductById(1)).thenReturn(null);
        assertThrows(ProductException.class, () -> service.addToWishlist(CUSTOMER, 1));
        verify(wishlistRepository, never()).save(any());
    }

    // Regression: ProductService's real /product/byId throws for a missing id rather than returning null (only
    // a mocked ProductClient in a test can return null), so this must be caught and translated, not left to
    // surface as a raw 500.
    @Test
    void addToWishlistThrowsWhenProductClientRejectsTheLookup() {
        when(productClient.getProductById(1)).thenThrow(declinedBy("byId", 400, "Product not found"));
        assertThrows(ProductException.class, () -> service.addToWishlist(CUSTOMER, 1));
        verify(wishlistRepository, never()).save(any());
    }

    @Test
    void addToWishlistSavesANewEntryWhenNotAlreadyPresent() {
        when(productClient.getProductById(1)).thenReturn(product(1, 9.99, 10));
        when(wishlistRepository.findByCustomerPhnoAndProductId(CUSTOMER, 1)).thenReturn(Optional.empty());
        Wishlist saved = new Wishlist();
        saved.setId(1L);
        saved.setCustomerPhno(CUSTOMER);
        saved.setProductId(1);
        when(wishlistRepository.save(any(Wishlist.class))).thenReturn(saved);

        Wishlist result = service.addToWishlist(CUSTOMER, 1);

        assertEquals(1, result.getProductId());
        verify(wishlistRepository).save(any(Wishlist.class));
    }

    // Idempotent: adding an already-wishlisted product returns the existing row instead of creating a duplicate.
    @Test
    void addToWishlistReturnsTheExistingEntryWithoutDuplicating() {
        when(productClient.getProductById(1)).thenReturn(product(1, 9.99, 10));
        Wishlist existing = new Wishlist();
        existing.setId(1L);
        existing.setCustomerPhno(CUSTOMER);
        existing.setProductId(1);
        when(wishlistRepository.findByCustomerPhnoAndProductId(CUSTOMER, 1)).thenReturn(Optional.of(existing));

        Wishlist result = service.addToWishlist(CUSTOMER, 1);

        assertEquals(existing, result);
        verify(wishlistRepository, never()).save(any());
    }

    @Test
    void getWishlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getWishlist(12345));
    }

    @Test
    void getWishlistDelegatesToTheRepository() {
        Wishlist w = new Wishlist();
        w.setCustomerPhno(CUSTOMER);
        w.setProductId(1);
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(w));
        assertEquals(1, service.getWishlist(CUSTOMER).size());
    }

    @Test
    void removeFromWishlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.removeFromWishlist(12345, 1));
        verifyNoInteractions(wishlistRepository);
    }

    @Test
    void removeFromWishlistDelegatesToTheRepository() {
        service.removeFromWishlist(CUSTOMER, 1);
        verify(wishlistRepository).deleteByCustomerPhnoAndProductId(CUSTOMER, 1);
    }

    // ---------- cancel() ----------

    private Cart placedOrder(long orderId, long paymentTransactionId, OrderItem... items) {
        Cart cart = cart(CUSTOMER, items);
        cart.setOrderId(orderId);
        cart.setStatus(OrderStatus.PLACED);
        cart.setPaymentTransactionId(paymentTransactionId);
        return cart;
    }

    @Test
    void cancelThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.cancel(42L, AUTH, null));
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
    }

    @Test
    void cancelThrowsWhenTheOrderIsAlreadyCancelled() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.cancel(42L, AUTH, null));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
    }

    @Test
    void cancelThrowsForAnOrderWithNoStoredPayment() {
        Cart cart = placedOrder(42L, 0, item(1, 1));
        cart.setPaymentTransactionId(null);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.cancel(42L, AUTH, null));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
    }

    // A declined/failed refund must leave the order exactly as it was - same fail-safe shape as a declined
    // payment leaving no order behind in order().
    @Test
    void cancelThrowsWhenTheRefundIsDeclinedAndTouchesNoStockOrOrder() {
        Cart cart = placedOrder(42L, 100000L, item(1, 2));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class)))
                .thenThrow(declinedBy("refund", 502, "We could not confirm your refund with the bank."));

        assertThrows(PaymentException.class, () -> service.cancel(42L, AUTH, null));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
        assertEquals(OrderStatus.PLACED, cart.getStatus());
        verifyNoInteractions(orderKafkaProducer);
    }

    @Test
    void cancelRefundsRestoresStockForEveryItemAndMarksTheOrderCancelled() {
        Cart cart = placedOrder(42L, 100000L, item(1, 2), item(2, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), eq(new RefundRequest("cancel-1"))))
                .thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.cancel(42L, AUTH, "cancel-1");

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
        verify(productClient).updateProductStock(SERVICE_KEY, 2, 1);
        verify(orderRepository).save(cart);
        verify(orderKafkaProducer).sendMessage(contains("Order cancelled successfully"));
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.CANCELLED, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderId());
    }

    // A broken notification channel must never turn a completed cancellation into an error.
    @Test
    void cancelKafkaFailureDoesNotFailTheCancellation() {
        Cart cart = placedOrder(42L, 100000L, item(1, 2));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        doThrow(new RuntimeException("kafka down")).when(orderKafkaProducer).sendMessage(any());

        Cart result = service.cancel(42L, AUTH, null);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
    }

    // Same regression as order(): an Idempotency-Key retry can hand back a not-actually-completed refund with
    // no exception, and that must not be enough to restore stock or mark the order cancelled.
    @Test
    void cancelRejectsARefundThatIsNotActuallyCompleted() {
        Cart cart = placedOrder(42L, 100000L, item(1, 2));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class)))
                .thenReturn(paymentResponse(100001, "NEEDS_RECONCILIATION"));

        assertThrows(PaymentException.class, () -> service.cancel(42L, AUTH, null));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
        assertEquals(OrderStatus.PLACED, cart.getStatus());
        verifyNoInteractions(orderKafkaProducer);
    }

    // ---------- ship() / deliver() ----------

    @Test
    void shipThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.ship(42L));
    }

    @Test
    void shipThrowsWhenTheOrderIsNotPlaced() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.ship(42L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shipMovesAPlacedOrderToShipped() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.ship(42L);

        assertEquals(OrderStatus.SHIPPED, result.getStatus());
        verify(orderKafkaProducer).sendMessage(contains("Order shipped"));
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.SHIPPED, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderId());
    }

    @Test
    void deliverThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.deliver(42L));
    }

    @Test
    void deliverThrowsWhenTheOrderIsNotShipped() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.deliver(42L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void deliverMovesAShippedOrderToDelivered() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.deliver(42L);

        assertEquals(OrderStatus.DELIVERED, result.getStatus());
        verify(orderKafkaProducer).sendMessage(contains("Order delivered"));
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.DELIVERED, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderId());
    }

    // ---------- getTracking() ----------

    @Test
    void getTrackingThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.existsById(42L)).thenReturn(false);
        assertThrows(OrderNotFoundException.class, () -> service.getTracking(42L));
        verifyNoInteractions(trackingEventRepository);
    }

    @Test
    void getTrackingReturnsTheOrdersTimelineOldestFirst() {
        when(orderRepository.existsById(42L)).thenReturn(true);
        TrackingEvent placed = new TrackingEvent();
        placed.setOrderId(42L);
        placed.setStatus(OrderStatus.PLACED);
        TrackingEvent shipped = new TrackingEvent();
        shipped.setOrderId(42L);
        shipped.setStatus(OrderStatus.SHIPPED);
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L))
                .thenReturn(List.of(placed, shipped));

        List<TrackingEvent> result = service.getTracking(42L);

        assertEquals(List.of(placed, shipped), result);
    }

    // Regression: cancellation must stop being available once an order has moved past PLACED, not just once
    // it's already CANCELLED.
    @Test
    void cancelThrowsForAShippedOrder() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.cancel(42L, AUTH, null));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
    }

    // ---------- ordersOfPhno ----------

    @Test
    void ordersOfPhnoRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.ordersOfPhno(555));
    }

    @Test
    void ordersOfPhnoDelegatesToTheRepository() {
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cart(CUSTOMER)));
        assertEquals(1, service.ordersOfPhno(CUSTOMER).size());
    }

    // ---------- deleteProduct ----------

    @Test
    void deleteProductRestoresStockAndRecomputesTotalPrice() {
        OrderItem toRemove = item(1, 2);
        toRemove.setId(101L);
        OrderItem toKeep = item(2, 1);
        toKeep.setId(102L);
        Cart cart = cart(CUSTOMER, toRemove, toKeep);
        cart.setTotalPrice(2 * 500.0 + 45.0); // 1045.0, matching the two items above
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cart));
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 8));

        List<Cart> result = service.deleteProduct(CUSTOMER, 1);

        assertEquals(1, result.get(0).getOrderItems().size());
        assertEquals(45.0, result.get(0).getTotalPrice());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
        verify(orderItemRepository).deleteById(toRemove.getId());
    }

    @Test
    void deleteProductRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.deleteProduct(555, 1));
    }

    // ---------- getProducts ----------

    @Test
    void getProductsDelegatesToTheProductClient() {
        when(productClient.findAll()).thenReturn(List.of(product(1, 9.99, 10)));
        assertEquals(1, service.getProducts().size());
    }

    @Test
    void getCouponsDelegatesToTheCouponRepository() {
        when(couponRepository.findAll()).thenReturn(List.of(coupon("SAVE10", 10, true)));
        assertEquals(1, service.getCoupons().size());
    }
}
