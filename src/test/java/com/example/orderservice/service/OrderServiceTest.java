package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.CreateUpiCollectRequest;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.PhonepeLoginRequest;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ModerationReviewsResult;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ProductReviewsResult;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.dto.AdminOrderRow;
import com.example.orderservice.dto.CouponSuggestion;
import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.RevenueTimeseries;
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
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CouponRedemptionRepository;
import com.example.orderservice.repository.CouponRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.LoyaltyTransactionRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.OrderItemRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    private CouponRedemptionRepository couponRedemptionRepository;
    @Mock
    private LoyaltyAccountRepository loyaltyAccountRepository;
    @Mock
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Mock
    private WishlistRepository wishlistRepository;
    @Mock
    private StockWaitlistRepository stockWaitlistRepository;
    @Mock
    private TrackingEventRepository trackingEventRepository;
    @Mock
    private ShippingAddressRepository shippingAddressRepository;
    @Mock
    private NotificationLogRepository notificationLogRepository;
    @Mock
    private ProductClient productClient;
    @Mock
    private PhonepeClient phonepeClient;
    @Mock
    private OrderKafkaProducer orderKafkaProducer;
    @Mock
    private CustomerNotifier customerNotifier;

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

    private LoyaltyAccount loyaltyAccount(long phno, int pointsBalance) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setCustomerPhno(phno);
        account.setPointsBalance(pointsBalance);
        return account;
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

    @Test
    void orderRejectsZeroOrNegativeQuantitiesBeforeAnythingHappens() {
        for (int qty : new int[]{0, -3}) {
            Cart cart = cart(CUSTOMER, item(1, qty));
            assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        }
        verifyNoInteractions(phonepeClient);
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderRejectsAnOrderWithNoItems() {
        assertThrows(ProductException.class, () -> service.order(cart(CUSTOMER), AUTH, null));
        verify(orderRepository, never()).save(any());
    }

    // Two lines for the same product must be checked against stock together, not one at a time.
    @Test
    void orderMergesDuplicateProductLinesBeforeTheStockCheck() {
        when(productClient.getProductById(1)).thenReturn(product(1, 45.0, 5));
        Cart cart = cart(CUSTOMER, item(1, 3), item(1, 3));

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verify(orderRepository, never()).save(any());
    }

    // The body is deserialized into the entity: a client-supplied orderId/item id would make save() overwrite an
    // existing (someone else's) order, so they are discarded.
    @Test
    void orderIgnoresClientSuppliedIdsAndSystemManagedFields() {
        when(productClient.getProductById(1)).thenReturn(product(1, 45.0, 5));
        OrderItem it = item(1, 1);
        it.setId(555L);
        it.setOrderId(999L);
        Cart cart = cart(CUSTOMER, it);
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setOrderId(999L);
        cart.setRefundedAmount(1000);
        java.util.List<Object[]> atSave = new ArrayList<>();
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Cart c = inv.getArgument(0);
            atSave.add(new Object[]{c.getOrderId(), c.getRefundedAmount(), c.getOrderItems().get(0).getId()});
            if (c.getOrderId() == null) {
                c.setOrderId(1L);
            }
            return c;
        });

        service.order(cart, AUTH, null);

        assertEquals(null, atSave.get(0)[0]);
        assertEquals(0.0, atSave.get(0)[1]);
        assertEquals(null, atSave.get(0)[2]);
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
        assertTrue(result.isPaid());
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

    // ---------- CASH / storefront checkout ----------

    // A CASH order never touches PhonepayService at all - no makePayment, no login, and the saved order has no
    // paymentTransactionId (there was nothing to charge).
    @Test
    void orderWithCashPaymentMethodNeverChargesAndSavesWithNoTransactionId() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPaymentMethod(PaymentMethod.CASH);

        Cart result = service.order(cart, null, null);

        assertEquals(PaymentMethod.CASH, result.getPaymentMethod());
        assertNull(result.getPaymentTransactionId());
        assertFalse(result.isPaid());
        assertEquals(500.0, result.getTotalPrice());
        verifyNoInteractions(phonepeClient);
        verify(orderKafkaProducer).sendMessage(contains("Order placed successfully"));
    }

    // The storefront checkout path: no Authorization token yet, only the buyer's own phone+PIN - OrderService
    // exchanges those for a token via PhonepayService's own /phonepe/login, then charges with it exactly like an
    // already-logged-in caller would.
    @Test
    void orderWithPhonePhnoAndPinLogsInAndChargesWithTheReturnedToken() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.login(new PhonepeLoginRequest(CUSTOMER, "1234")))
                .thenReturn(new PhonepeLoginResponse("fresh-token", Instant.parse("2026-09-25T11:00:00Z"), CUSTOMER, "Buyer"));
        when(phonepeClient.makePayment(eq("Bearer fresh-token"), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        Cart result = service.order(cart, null, null, CUSTOMER, "1234");

        assertEquals(100000L, result.getPaymentTransactionId());
        verify(phonepeClient).makePayment(eq("Bearer fresh-token"), any(PaymentRequest.class));
    }

    // An Authorization header the caller already has always wins over payerPhno/payerPin - never silently
    // re-authenticate behind an already-logged-in caller's back.
    @Test
    void orderPrefersAnExistingAuthorizationTokenOverPayerCredentials() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        service.order(cart, AUTH, null, CUSTOMER, "1234");

        verify(phonepeClient, never()).login(any());
        verify(phonepeClient).makePayment(eq(AUTH), any(PaymentRequest.class));
    }

    // A PHONEPE checkout with neither a token nor phone+PIN must fail before anything happens - same fail-fast
    // reasoning as every other checkout precondition.
    @Test
    void orderThrowsWhenNoTokenOrPayerCredentialsAreSupplied() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Cart cart = cart(CUSTOMER, item(1, 1));

        assertThrows(ProductException.class, () -> service.order(cart, null, null, null, null));
        verifyNoInteractions(phonepeClient);
        verify(orderRepository, never()).save(any());
    }

    // A wrong PIN surfaces as PhonepayService's own 401, relayed as-is - OrderService never validates the PIN
    // itself.
    @Test
    void orderRelaysAnInvalidPinFromPhonepayServiceLogin() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.login(new PhonepeLoginRequest(CUSTOMER, "0000")))
                .thenThrow(declinedBy("login", 401, "Invalid phone number or PIN"));
        Cart cart = cart(CUSTOMER, item(1, 1));

        PaymentException ex = assertThrows(PaymentException.class,
                () -> service.order(cart, null, null, CUSTOMER, "0000"));

        assertEquals("Invalid phone number or PIN", ex.getMessage());
        verify(orderRepository, never()).save(any());
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
    void orderThrowsForAnExpiredCoupon() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Coupon expired = coupon("OLD10", 10, true);
        expired.setExpiryDate(Instant.now().minusSeconds(60));
        when(couponRepository.findById("OLD10")).thenReturn(Optional.of(expired));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("OLD10");

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderThrowsWhenTheCouponsGlobalRedemptionLimitIsReached() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Coupon maxedOut = coupon("SAVE10", 10, true);
        maxedOut.setMaxRedemptions(5);
        maxedOut.setRedemptionCount(5);
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(maxedOut));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("SAVE10");

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderThrowsWhenTheCustomerHasAlreadyReachedThePerCustomerCouponLimit() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Coupon oncePerCustomer = coupon("SAVE10", 10, true);
        oncePerCustomer.setPerCustomerLimit(1);
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(oncePerCustomer));
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCouponCode("SAVE10");
        redemption.setCustomerPhno(CUSTOMER);
        redemption.setCount(1);
        when(couponRedemptionRepository.findByCouponCodeAndCustomerPhno("SAVE10", CUSTOMER))
                .thenReturn(Optional.of(redemption));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("SAVE10");

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderRecordsCouponRedemptionOnlyAfterASuccessfulCharge() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Coupon save10 = coupon("SAVE10", 10, true);
        save10.setPerCustomerLimit(3);
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(save10));
        when(couponRedemptionRepository.findByCouponCodeAndCustomerPhno("SAVE10", CUSTOMER))
                .thenReturn(Optional.empty());
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("SAVE10");

        service.order(cart, AUTH, null);

        assertEquals(1, save10.getRedemptionCount());
        verify(couponRepository).save(save10);
        ArgumentCaptor<CouponRedemption> captor = ArgumentCaptor.forClass(CouponRedemption.class);
        verify(couponRedemptionRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getCount());
        assertEquals(CUSTOMER, captor.getValue().getCustomerPhno());
    }

    // A declined payment must not consume a redemption, same fail-safe reasoning as stock never being touched.
    @Test
    void orderDoesNotRecordACouponRedemptionWhenThePaymentIsDeclined() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(coupon("SAVE10", 10, true)));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class)))
                .thenThrow(declinedBy("payment", 402, "Insufficient funds"));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setCouponCode("SAVE10");

        assertThrows(PaymentException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(couponRedemptionRepository);
        verify(couponRepository, never()).save(any());
    }

    @Test
    void orderThrowsForAnUnknownShippingAddressAndTouchesNoPaymentOrStock() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(shippingAddressRepository.findById(99L)).thenReturn(Optional.empty());
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setShippingAddressId(99L);

        assertThrows(OrderNotFoundException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(phonepeClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderThrowsWhenTheShippingAddressBelongsToAnotherCustomer() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        ShippingAddress address = new ShippingAddress();
        address.setId(99L);
        address.setCustomerPhno(1111111111L);
        when(shippingAddressRepository.findById(99L)).thenReturn(Optional.of(address));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setShippingAddressId(99L);

        assertThrows(OrderNotFoundException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(phonepeClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderWithNoShippingAddressIdTouchesNoAddressLookup() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        service.order(cart, AUTH, null);

        verifyNoInteractions(shippingAddressRepository);
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

    @Test
    void saveCouponRejectsANonPositiveMaxRedemptions() {
        Coupon input = coupon("SAVE10", 10, true);
        input.setMaxRedemptions(0);
        assertThrows(ProductException.class, () -> service.saveCoupon(input));
        verify(couponRepository, never()).save(any());
    }

    @Test
    void saveCouponRejectsANonPositivePerCustomerLimit() {
        Coupon input = coupon("SAVE10", 10, true);
        input.setPerCustomerLimit(0);
        assertThrows(ProductException.class, () -> service.saveCoupon(input));
        verify(couponRepository, never()).save(any());
    }

    // redemptionCount is system-managed (incremented only by a successful order) - an admin update to a coupon's
    // discount or expiry must not silently reset accumulated usage back to zero.
    @Test
    void saveCouponPreservesTheRedemptionCountAcrossAnUpdate() {
        Coupon existing = coupon("SAVE10", 10, true);
        existing.setRedemptionCount(7);
        when(couponRepository.findById("SAVE10")).thenReturn(Optional.of(existing));
        when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        Coupon update = coupon("SAVE10", 15, true);
        Coupon result = service.saveCoupon(update);

        assertEquals(7, result.getRedemptionCount());
    }

    // ---------- loyalty points redemption at checkout ----------

    @Test
    void orderRedeemsPointsOnTopOfAnyCouponDiscount() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(loyaltyAccountRepository.findById(CUSTOMER))
                .thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 100)));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        Cart result = service.order(cart, AUTH, null);

        assertEquals(450.0, result.getTotalPrice());
        assertEquals(50, result.getPointsRedeemed());
        verify(phonepeClient).makePayment(eq(AUTH), eq(new PaymentRequest(new BigDecimal("450.00"), "Order payment", null)));
    }

    @Test
    void orderWithNoPointsRedeemedTouchesNoLoyaltyLookup() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));

        Cart result = service.order(cart, AUTH, null);

        assertEquals(500.0, result.getTotalPrice());
        verifyNoInteractions(loyaltyAccountRepository);
    }

    @Test
    void orderThrowsWhenRedeemingMorePointsThanTheBalanceHolds() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(loyaltyAccountRepository.findById(CUSTOMER))
                .thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 20)));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderThrowsWhenRedeemingMorePointsThanTheRemainingPrice() {
        when(productClient.getProductById(1)).thenReturn(product(1, 30.0, 10));
        when(loyaltyAccountRepository.findById(CUSTOMER))
                .thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 100)));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderRejectsNegativePointsRedeemed() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(-5);

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderDeductsRedeemedPointsFromTheBalanceOnlyAfterASuccessfulCharge() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class))).thenReturn(paymentResponse(100000));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        service.order(cart, AUTH, null);

        assertEquals(50, account.getPointsBalance());
        verify(loyaltyAccountRepository).save(account);
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(-50, captor.getValue().getPoints());
        assertEquals(LoyaltyTransactionType.REDEEMED, captor.getValue().getType());
        assertEquals(CUSTOMER, captor.getValue().getCustomerPhno());
    }

    @Test
    void orderDoesNotDeductPointsWhenThePaymentIsDeclined() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(loyaltyAccountRepository.findById(CUSTOMER))
                .thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 100)));
        when(phonepeClient.makePayment(eq(AUTH), any(PaymentRequest.class)))
                .thenThrow(declinedBy("payment", 402, "Insufficient funds"));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        assertThrows(PaymentException.class, () -> service.order(cart, AUTH, null));

        verifyNoInteractions(loyaltyTransactionRepository);
        verify(loyaltyAccountRepository, never()).save(any());
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

    // The snapshot getPriceDropAlerts() later compares the current price against.
    @Test
    void addToWishlistSnapshotsTheProductsCurrentPrice() {
        when(productClient.getProductById(1)).thenReturn(product(1, 499.0, 10));
        when(wishlistRepository.findByCustomerPhnoAndProductId(CUSTOMER, 1)).thenReturn(Optional.empty());
        when(wishlistRepository.save(any(Wishlist.class))).thenAnswer(inv -> inv.getArgument(0));

        Wishlist result = service.addToWishlist(CUSTOMER, 1);

        assertEquals(499.0, result.getPriceWhenAdded());
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

    // ---------- getPriceDropAlerts() ----------

    private Wishlist wishlistItem(int productId, Double priceWhenAdded) {
        Wishlist w = new Wishlist();
        w.setCustomerPhno(CUSTOMER);
        w.setProductId(productId);
        w.setPriceWhenAdded(priceWhenAdded);
        return w;
    }

    @Test
    void getPriceDropAlertsRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getPriceDropAlerts(12345));
        verifyNoInteractions(wishlistRepository);
    }

    @Test
    void getPriceDropAlertsReturnsOnlyItemsWhoseCurrentPriceIsLower() {
        when(wishlistRepository.findByCustomerPhno(CUSTOMER))
                .thenReturn(List.of(wishlistItem(1, 500.0), wishlistItem(2, 200.0)));
        when(productClient.getProductById(1)).thenReturn(product(1, 450.0, 10));
        when(productClient.getProductById(2)).thenReturn(product(2, 200.0, 10));

        List<WishlistPriceAlert> alerts = service.getPriceDropAlerts(CUSTOMER);

        assertEquals(1, alerts.size());
        assertEquals(1, alerts.get(0).productId());
        assertEquals(500.0, alerts.get(0).priceWhenAdded());
        assertEquals(450.0, alerts.get(0).currentPrice());
        assertEquals(50.0, alerts.get(0).priceDrop());
    }

    @Test
    void getPriceDropAlertsSkipsAnItemWithNoStoredSnapshot() {
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(wishlistItem(1, null)));

        List<WishlistPriceAlert> alerts = service.getPriceDropAlerts(CUSTOMER);

        assertTrue(alerts.isEmpty());
        verifyNoInteractions(productClient);
    }

    @Test
    void getPriceDropAlertsSkipsAnItemWhosePriceRoseOrStayedTheSame() {
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(wishlistItem(1, 500.0)));
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));

        assertTrue(service.getPriceDropAlerts(CUSTOMER).isEmpty());
    }

    // A product that's since been removed from the catalog must not blow up the whole list - it's simply
    // skipped, same reasoning addToWishlist already applies to a Feign failure.
    @Test
    void getPriceDropAlertsSkipsAnItemWhoseProductLookupFails() {
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(wishlistItem(1, 500.0)));
        when(productClient.getProductById(1)).thenThrow(declinedBy("byId", 404, "Product not found"));

        assertTrue(service.getPriceDropAlerts(CUSTOMER).isEmpty());
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

    // ---------- back-in-stock waitlist ----------

    @Test
    void addToWaitlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.addToWaitlist(12345, 1));
        verifyNoInteractions(productClient, stockWaitlistRepository);
    }

    @Test
    void addToWaitlistThrowsWhenTheProductDoesNotExist() {
        when(productClient.getProductById(1)).thenReturn(null);
        assertThrows(ProductException.class, () -> service.addToWaitlist(CUSTOMER, 1));
        verify(stockWaitlistRepository, never()).save(any());
    }

    @Test
    void addToWaitlistThrowsWhenProductClientRejectsTheLookup() {
        when(productClient.getProductById(1)).thenThrow(declinedBy("byId", 400, "Product not found"));
        assertThrows(ProductException.class, () -> service.addToWaitlist(CUSTOMER, 1));
        verify(stockWaitlistRepository, never()).save(any());
    }

    @Test
    void addToWaitlistSavesANewEntryWhenNotAlreadyPresent() {
        when(productClient.getProductById(1)).thenReturn(product(1, 9.99, 0));
        when(stockWaitlistRepository.findByCustomerPhnoAndProductId(CUSTOMER, 1)).thenReturn(Optional.empty());
        StockWaitlist saved = new StockWaitlist();
        saved.setId(1L);
        saved.setCustomerPhno(CUSTOMER);
        saved.setProductId(1);
        when(stockWaitlistRepository.save(any(StockWaitlist.class))).thenReturn(saved);

        StockWaitlist result = service.addToWaitlist(CUSTOMER, 1);

        assertEquals(1, result.getProductId());
        verify(stockWaitlistRepository).save(any(StockWaitlist.class));
    }

    // Idempotent: adding an already-waitlisted product returns the existing row instead of creating a duplicate.
    @Test
    void addToWaitlistReturnsTheExistingEntryWithoutDuplicating() {
        when(productClient.getProductById(1)).thenReturn(product(1, 9.99, 0));
        StockWaitlist existing = new StockWaitlist();
        existing.setId(1L);
        existing.setCustomerPhno(CUSTOMER);
        existing.setProductId(1);
        when(stockWaitlistRepository.findByCustomerPhnoAndProductId(CUSTOMER, 1)).thenReturn(Optional.of(existing));

        StockWaitlist result = service.addToWaitlist(CUSTOMER, 1);

        assertEquals(existing, result);
        verify(stockWaitlistRepository, never()).save(any());
    }

    @Test
    void getWaitlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getWaitlist(12345));
        verifyNoInteractions(stockWaitlistRepository);
    }

    private StockWaitlist waitlistItem(int productId) {
        StockWaitlist w = new StockWaitlist();
        w.setCustomerPhno(CUSTOMER);
        w.setProductId(productId);
        return w;
    }

    @Test
    void getWaitlistReportsLiveStockPerEntry() {
        when(stockWaitlistRepository.findByCustomerPhno(CUSTOMER))
                .thenReturn(List.of(waitlistItem(1), waitlistItem(2)));
        when(productClient.getProductById(1)).thenReturn(product(1, 9.99, 0));
        when(productClient.getProductById(2)).thenReturn(product(2, 4.99, 5));

        List<WaitlistStatus> statuses = service.getWaitlist(CUSTOMER);

        assertEquals(2, statuses.size());
        assertFalse(statuses.get(0).inStock());
        assertEquals(0, statuses.get(0).currentStock());
        assertTrue(statuses.get(1).inStock());
        assertEquals(5, statuses.get(1).currentStock());
    }

    // A product that's since been removed from the catalog must not blow up the whole list - it's simply
    // skipped, same reasoning getPriceDropAlerts() already applies.
    @Test
    void getWaitlistSkipsAnItemWhoseProductLookupFails() {
        when(stockWaitlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(waitlistItem(1)));
        when(productClient.getProductById(1)).thenThrow(declinedBy("byId", 404, "Product not found"));

        assertTrue(service.getWaitlist(CUSTOMER).isEmpty());
    }

    @Test
    void removeFromWaitlistRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.removeFromWaitlist(12345, 1));
        verifyNoInteractions(stockWaitlistRepository);
    }

    @Test
    void removeFromWaitlistDelegatesToTheRepository() {
        service.removeFromWaitlist(CUSTOMER, 1);
        verify(stockWaitlistRepository).deleteByCustomerPhnoAndProductId(CUSTOMER, 1);
    }

    // ---------- order() via UPI collect request ----------

    private UpiCollectRequestResponse upiCollectResponse(String status) {
        return upiCollectResponse(status, null);
    }

    private UpiCollectRequestResponse upiCollectResponse(String status, Long resultTransactionId) {
        return new UpiCollectRequestResponse(1L, "OrderService-42", CUSTOMER, "9876543210@charanpe",
                new BigDecimal("450.00"), "Order payment", status, Instant.parse("2026-09-25T10:00:00Z"),
                Instant.parse("2026-09-25T10:04:00Z"), null, resultTransactionId);
    }

    @Test
    void orderWithPayerUpiIdCreatesAPendingPaymentOrderAndReservesStock() {
        when(productClient.getProductById(1)).thenReturn(product(1, 450.0, 10));
        stubCartSaveAssignsAnId();
        Cart cart = cart(CUSTOMER, item(1, 1));

        Cart result = service.order(cart, null, null, null, null, "9876543210@charanpe");

        assertEquals(OrderStatus.PENDING_PAYMENT, result.getStatus());
        assertFalse(result.isPaid());
        assertEquals("9876543210@charanpe", result.getUpiId());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, -1);
        verify(phonepeClient).createUpiCollectRequest(eq(SERVICE_KEY),
                eq(new CreateUpiCollectRequest("OrderService-42", "9876543210@charanpe",
                        new BigDecimal("450.00"), "Order payment")));
        // Coupon/points are never recorded for a still-unpaid order - only finalizePaidOrder() does that.
        verifyNoInteractions(loyaltyAccountRepository);
    }

    // If PhonepayService refuses to even create the collect request, nothing should be left behind - same
    // fail-safe shape a declined synchronous charge already gives.
    @Test
    void orderWithPayerUpiIdRollsBackStockAndDeletesOrderWhenCollectRequestFails() {
        when(productClient.getProductById(1)).thenReturn(product(1, 450.0, 10));
        stubCartSaveAssignsAnId();
        when(phonepeClient.createUpiCollectRequest(eq(SERVICE_KEY), any(CreateUpiCollectRequest.class)))
                .thenThrow(declinedBy("upi/collect", 400, "Invalid UPI ID"));
        Cart cart = cart(CUSTOMER, item(1, 1));

        PaymentException ex = assertThrows(PaymentException.class,
                () -> service.order(cart, null, null, null, null, "bad-upi-id"));

        assertEquals("Invalid UPI ID", ex.getMessage());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, -1);
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
        verify(orderRepository).delete(any(Cart.class));
    }

    // ---------- checkPendingPayment() ----------

    private Cart pendingUpiCart(Instant deadline) {
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setOrderId(42L);
        cart.setStatus(OrderStatus.PENDING_PAYMENT);
        cart.setTotalPrice(450.0);
        cart.setUpiId("9876543210@charanpe");
        cart.setPaymentDeadline(deadline);
        return cart;
    }

    @Test
    void checkPendingPaymentThrowsWhenOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.checkPendingPayment(42L));
    }

    @Test
    void checkPendingPaymentIsANoOpForAnOrderThatIsNotPendingPayment() {
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setOrderId(42L);
        cart.setStatus(OrderStatus.PLACED);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.PLACED, result.getStatus());
        verifyNoInteractions(phonepeClient);
    }

    // OrderService's own deadline is authoritative - checked BEFORE ever asking PhonepayService, so an expired
    // order is cancelled even if PhonepayService's own collect-request expiry disagrees.
    @Test
    void checkPendingPaymentCancelsAndRestoresStockWhenOrderServiceDeadlineHasPassed() {
        Cart cart = pendingUpiCart(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
        verify(phonepeClient, never()).getUpiCollectRequest(any(), any());
        verify(phonepeClient, never()).refund(any(), anyLong(), any());
    }

    @Test
    void checkPendingPaymentLeavesTheOrderUnchangedWhilePhonepayServiceStillShowsPending() {
        Cart cart = pendingUpiCart(Instant.now().plus(2, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.getUpiCollectRequest(SERVICE_KEY, "OrderService-42"))
                .thenReturn(upiCollectResponse("PENDING"));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.PENDING_PAYMENT, result.getStatus());
        verify(orderRepository, never()).save(any());
    }

    // PhonepayService being briefly unreachable must not cancel a still-valid order - only an explicit
    // DECLINED/EXPIRED answer, or OrderService's own deadline, ends it early.
    @Test
    void checkPendingPaymentLeavesTheOrderUnchangedWhenPhonepayServiceIsUnreachable() {
        Cart cart = pendingUpiCart(Instant.now().plus(2, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.getUpiCollectRequest(SERVICE_KEY, "OrderService-42"))
                .thenThrow(declinedBy("upi/collect/42", 503, "unreachable"));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.PENDING_PAYMENT, result.getStatus());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkPendingPaymentFinalizesTheOrderWhenApproved() {
        Cart cart = pendingUpiCart(Instant.now().plus(2, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.getUpiCollectRequest(SERVICE_KEY, "OrderService-42"))
                .thenReturn(upiCollectResponse("APPROVED", 777L));
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.PLACED, result.getStatus());
        assertTrue(result.isPaid());
        assertEquals(777L, result.getPaymentTransactionId());
        assertNull(result.getPaymentDeadline());
        verify(productClient, never()).updateProductStock(any(), anyInt(), eq(1));
    }

    @Test
    void checkPendingPaymentCancelsAndRestoresStockWhenDeclined() {
        Cart cart = pendingUpiCart(Instant.now().plus(2, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.getUpiCollectRequest(SERVICE_KEY, "OrderService-42"))
                .thenReturn(upiCollectResponse("DECLINED"));
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
        // Never actually charged, so there's nothing for a refund to reverse.
        verify(phonepeClient, never()).refund(any(), anyLong(), any());
    }

    @Test
    void checkPendingPaymentCancelsAndRestoresStockWhenExpiredOnPhonepayService() {
        Cart cart = pendingUpiCart(Instant.now().plus(2, ChronoUnit.MINUTES));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.getUpiCollectRequest(SERVICE_KEY, "OrderService-42"))
                .thenReturn(upiCollectResponse("EXPIRED"));
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart result = service.checkPendingPayment(42L);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
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
        when(phonepeClient.refund(eq(AUTH), eq(100000L), eq(new RefundRequest("cancel-1", null))))
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

    // A CASH order was never charged through PhonepayService, so cancelling it needs no Authorization token and
    // makes no refund call at all - only the stock restoration and status change happen.
    @Test
    void cancelOfACashOrderRestoresStockWithNoRefundCall() {
        Cart cart = placedOrder(42L, 0, item(1, 2));
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setPaymentTransactionId(null);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.cancel(42L, null, null);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verifyNoInteractions(phonepeClient);
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
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
        verify(customerNotifier).notifyStatusChange(cart, OrderStatus.SHIPPED);
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
        verify(customerNotifier).notifyStatusChange(cart, OrderStatus.DELIVERED);
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.DELIVERED, captor.getValue().getStatus());
        assertEquals(42L, captor.getValue().getOrderId());
    }

    // 1 point per RUPEES_PER_POINT (₹10) of the order's actual total, earned only once DELIVERED.
    @Test
    void deliverEarnsLoyaltyPointsBasedOnTheOrdersTotal() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        cart.setTotalPrice(455.0);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.empty());

        service.deliver(42L);

        ArgumentCaptor<LoyaltyAccount> accountCaptor = ArgumentCaptor.forClass(LoyaltyAccount.class);
        verify(loyaltyAccountRepository).save(accountCaptor.capture());
        assertEquals(45, accountCaptor.getValue().getPointsBalance());
        ArgumentCaptor<LoyaltyTransaction> txCaptor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(txCaptor.capture());
        assertEquals(45, txCaptor.getValue().getPoints());
        assertEquals(LoyaltyTransactionType.EARNED, txCaptor.getValue().getType());
        assertEquals(42L, txCaptor.getValue().getOrderId());
    }

    @Test
    void deliverEarnsNoPointsWhenTheTotalIsBelowTheConversionThreshold() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        cart.setTotalPrice(9.0);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        service.deliver(42L);

        verifyNoInteractions(loyaltyAccountRepository, loyaltyTransactionRepository);
    }

    // The tier multiplier applied is based on lifetime points BEFORE this order - a SILVER customer (>=500
    // lifetime points already) earns at 1.25x on this delivery.
    @Test
    void deliverAppliesTheCustomersTierMultiplierWhenEarningPoints() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        cart.setTotalPrice(500.0);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 50);
        account.setLifetimePointsEarned(500);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        service.deliver(42L);

        // base = 500/10 = 50, SILVER multiplier 1.25 -> 62
        assertEquals(50 + 62, account.getPointsBalance());
        assertEquals(500 + 62, account.getLifetimePointsEarned());
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(62, captor.getValue().getPoints());
    }

    // ---------- markPaid() ----------

    @Test
    void markPaidThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.markPaid(42L));
    }

    @Test
    void markPaidThrowsForAPhonepeOrder() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setPaymentMethod(PaymentMethod.PHONEPE);
        cart.setPaid(true);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.markPaid(42L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markPaidThrowsWhenAlreadyPaid() {
        Cart cart = placedOrder(42L, 0, item(1, 1));
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setPaymentTransactionId(null);
        cart.setPaid(true);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.markPaid(42L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markPaidMarksAnUnpaidCashOrderAsPaid() {
        Cart cart = placedOrder(42L, 0, item(1, 1));
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setPaymentTransactionId(null);
        cart.setPaid(false);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.markPaid(42L);

        assertTrue(result.isPaid());
        verify(orderKafkaProducer).sendMessage(contains("Order marked paid"));
    }

    // ---------- getInvoice() ----------

    @Test
    void getInvoiceThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.getInvoice(42L, CUSTOMER));
    }

    @Test
    void getInvoiceHidesAnotherCustomersOrderAsNotFound() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart(CUSTOMER, item(1, 1))));
        assertThrows(OrderNotFoundException.class, () -> service.getInvoice(42L, CUSTOMER + 1));
    }

    @Test
    void getInvoiceBuildsLinesTotalsAndAddress() {
        Cart order = cart(CUSTOMER, item(1, 2), item(2, 1));
        order.setCouponCode("WELCOME10");
        order.setDiscountAmount(10.0);
        order.setPointsRedeemed(5);
        order.setTotalPrice(85.0);
        order.setShippingAddressId(7L);
        order.setPaymentMethod(PaymentMethod.CASH);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(productClient.getProductById(1)).thenReturn(product(1, 40.0, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 20.0, 5));
        TrackingEvent placed = new TrackingEvent();
        placed.setTimestamp(java.time.Instant.parse("2026-10-01T10:00:00Z"));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of(placed));
        ShippingAddress address = new ShippingAddress();
        address.setLine1("1 Main St");
        address.setCity("Pune");
        address.setState("MH");
        address.setPincode("411001");
        when(shippingAddressRepository.findById(7L)).thenReturn(Optional.of(address));

        Invoice invoice = service.getInvoice(42L, CUSTOMER);

        assertEquals(2, invoice.lines().size());
        assertEquals(80.0, invoice.lines().get(0).lineTotal());
        assertEquals(85.0, invoice.totalPrice());
        assertEquals(5, invoice.pointsRedeemed());
        assertEquals("CASH", invoice.paymentMethod());
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00Z"), invoice.placedAt());
        assertEquals("1 Main St, Pune, MH, 411001", invoice.shippingAddress());
    }

    @Test
    void getInvoiceFallsBackWhenAProductIsGoneFromTheCatalog() {
        Cart order = cart(CUSTOMER, item(9, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(productClient.getProductById(9)).thenThrow(declinedBy("byId", 404, "Product not found"));

        Invoice invoice = service.getInvoice(42L, CUSTOMER);

        assertEquals("Product #9", invoice.lines().get(0).productName());
        assertEquals(0, invoice.pointsRedeemed());
        assertNull(invoice.placedAt());
        assertNull(invoice.shippingAddress());
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

    // ---------- getNotifications() ----------

    @Test
    void getNotificationsThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.existsById(42L)).thenReturn(false);
        assertThrows(OrderNotFoundException.class, () -> service.getNotifications(42L));
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void getNotificationsReturnsTheOrdersDispatchedNotifications() {
        when(orderRepository.existsById(42L)).thenReturn(true);
        NotificationLog shipped = new NotificationLog();
        shipped.setOrderId(42L);
        shipped.setEventType(OrderStatus.SHIPPED);
        when(notificationLogRepository.findByOrderIdOrderBySentAtAsc(42L)).thenReturn(List.of(shipped));

        List<NotificationLog> result = service.getNotifications(42L);

        assertEquals(List.of(shipped), result);
    }

    // ---------- getNotificationsForCustomer() ----------

    @Test
    void getNotificationsForCustomerRejectsAnInvalidPhno() {
        assertThrows(ProductException.class, () -> service.getNotificationsForCustomer(12345L));
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void getNotificationsForCustomerReturnsEmptyWithoutQueryingWhenTheCustomerHasNoOrders() {
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of());

        assertTrue(service.getNotificationsForCustomer(CUSTOMER).isEmpty());
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void getNotificationsForCustomerAggregatesAcrossAllOfTheCustomersOrders() {
        Cart orderA = placedOrder(1L, CUSTOMER, item(1, 1));
        Cart orderB = placedOrder(2L, CUSTOMER, item(2, 1));
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(orderA, orderB));
        NotificationLog delivered = new NotificationLog();
        delivered.setOrderId(2L);
        delivered.setEventType(OrderStatus.DELIVERED);
        when(notificationLogRepository.findByOrderIdInOrderBySentAtDesc(List.of(1L, 2L))).thenReturn(List.of(delivered));

        List<NotificationLog> result = service.getNotificationsForCustomer(CUSTOMER);

        assertEquals(List.of(delivered), result);
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

    // ---------- returnOrder() ----------

    private Cart deliveredOrder(long orderId, long paymentTransactionId, OrderItem... items) {
        Cart cart = placedOrder(orderId, paymentTransactionId, items);
        cart.setStatus(OrderStatus.DELIVERED);
        return cart;
    }

    @Test
    void returnOrderRejectsABlankReason() {
        assertThrows(ProductException.class, () -> service.returnOrder(42L, AUTH, null, " "));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void returnOrderThrowsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.returnOrder(42L, AUTH, null, "damaged"));
    }

    @Test
    void returnOrderThrowsWhenTheOrderIsNotDelivered() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.returnOrder(42L, AUTH, null, "damaged"));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
    }

    @Test
    void returnOrderThrowsForAnOrderWithNoStoredPayment() {
        Cart cart = deliveredOrder(42L, 0, item(1, 1));
        cart.setPaymentTransactionId(null);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.returnOrder(42L, AUTH, null, "damaged"));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
    }

    @Test
    void returnOrderThrowsWhenTheReturnWindowHasExpired() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        TrackingEvent delivered = new TrackingEvent();
        delivered.setOrderId(42L);
        delivered.setStatus(OrderStatus.DELIVERED);
        delivered.setTimestamp(Instant.now().minus(8, java.time.temporal.ChronoUnit.DAYS));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of(delivered));

        assertThrows(ProductException.class, () -> service.returnOrder(42L, AUTH, null, "damaged"));
        verify(phonepeClient, never()).refund(any(), anyInt(), any());
    }

    @Test
    void returnOrderWithNoDeliveredTrackingEventSkipsTheWindowCheck() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.returnOrder(42L, AUTH, null, "damaged");

        assertEquals(OrderStatus.RETURNED, result.getStatus());
    }

    @Test
    void returnOrderRefundsRestoresStockAndMarksTheOrderReturned() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 2), item(2, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        TrackingEvent delivered = new TrackingEvent();
        delivered.setOrderId(42L);
        delivered.setStatus(OrderStatus.DELIVERED);
        delivered.setTimestamp(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of(delivered));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), eq(new RefundRequest("return-1", null))))
                .thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.returnOrder(42L, AUTH, "return-1", "damaged in transit");

        assertEquals(OrderStatus.RETURNED, result.getStatus());
        assertEquals("damaged in transit", result.getReturnReason());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
        verify(productClient).updateProductStock(SERVICE_KEY, 2, 1);
        verify(orderKafkaProducer).sendMessage(contains("Order returned successfully"));
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(captor.capture());
        assertEquals(OrderStatus.RETURNED, captor.getValue().getStatus());
    }

    // Mirrors order()'s storefront path - the customer-facing return button has no stored session token either,
    // only a phone+PIN entered fresh for this call (see resolveBuyerToken).
    @Test
    void returnOrderWithPhonePhnoAndPinLogsInAndRefundsWithTheReturnedToken() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 2));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.login(new PhonepeLoginRequest(CUSTOMER, "1234")))
                .thenReturn(new PhonepeLoginResponse("fresh-token", Instant.parse("2026-09-25T11:00:00Z"), CUSTOMER, "Buyer"));
        when(phonepeClient.refund(eq("Bearer fresh-token"), eq(100000L), any(RefundRequest.class)))
                .thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.returnOrder(42L, null, null, "damaged", CUSTOMER, "1234");

        assertEquals(OrderStatus.RETURNED, result.getStatus());
        verify(phonepeClient).refund(eq("Bearer fresh-token"), eq(100000L), any(RefundRequest.class));
    }

    private LoyaltyTransaction earnedTransaction(long orderId, int points) {
        LoyaltyTransaction tx = new LoyaltyTransaction();
        tx.setOrderId(orderId);
        tx.setPoints(points);
        tx.setType(LoyaltyTransactionType.EARNED);
        return tx;
    }

    // A return reverses the EXACT points earned at delivery time (looked up from that order's own EARNED
    // transaction) - otherwise a refunded order would still leave the customer with points earned on money they
    // no longer paid.
    @Test
    void returnOrderClawsBackThePointsEarnedAtDelivery() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyTransactionRepository.findByOrderIdAndType(42L, LoyaltyTransactionType.EARNED))
                .thenReturn(Optional.of(earnedTransaction(42L, 45)));
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        service.returnOrder(42L, AUTH, null, "damaged");

        assertEquals(55, account.getPointsBalance());
        verify(loyaltyAccountRepository).save(account);
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(-45, captor.getValue().getPoints());
        assertEquals(LoyaltyTransactionType.ADJUSTED, captor.getValue().getType());
    }

    // The customer may have already spent those points on a different order - the clawback must clamp at zero
    // rather than driving the balance negative.
    @Test
    void returnOrderClawsBackNoMorePointsThanTheCurrentBalanceHolds() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyTransactionRepository.findByOrderIdAndType(42L, LoyaltyTransactionType.EARNED))
                .thenReturn(Optional.of(earnedTransaction(42L, 45)));
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 10);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        service.returnOrder(42L, AUTH, null, "damaged");

        assertEquals(0, account.getPointsBalance());
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(-10, captor.getValue().getPoints());
    }

    @Test
    void returnOrderTouchesNoLoyaltyAccountWhenTheOrderHasNoEarnedTransaction() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 1));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyTransactionRepository.findByOrderIdAndType(42L, LoyaltyTransactionType.EARNED))
                .thenReturn(Optional.empty());

        service.returnOrder(42L, AUTH, null, "damaged");

        verifyNoInteractions(loyaltyAccountRepository);
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    @Test
    void returnOrderThrowsWhenTheRefundIsDeclinedAndTouchesNoStockOrOrder() {
        Cart cart = deliveredOrder(42L, 100000L, item(1, 2));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class)))
                .thenThrow(declinedBy("refund", 502, "We could not confirm your refund with the bank."));

        assertThrows(PaymentException.class, () -> service.returnOrder(42L, AUTH, null, "damaged"));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
        assertEquals(OrderStatus.DELIVERED, cart.getStatus());
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
    void deleteProductLeavesNonPlacedOrdersAloneSoStockIsNotRestoredTwice() {
        OrderItem it = item(1, 2);
        it.setId(101L);
        Cart cancelled = cart(CUSTOMER, it);
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cancelled));

        service.deleteProduct(CUSTOMER, 1);

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        assertEquals(1, cancelled.getOrderItems().size());
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
    void getProductsMakesRelativeImageUrlsPointAtProductService() {
        Product p = product(1, 10.0, 5);
        p.setProductImageUrl("/uploads/a.png");
        Product q = product(2, 10.0, 5);
        q.setProductImageUrl("http://cdn.example/x.png");
        when(productClient.findAll()).thenReturn(List.of(p, q));

        List<Product> result = service.getProducts();

        assertEquals("http://localhost:8082/uploads/a.png", result.get(0).getProductImageUrl());
        assertEquals("http://cdn.example/x.png", result.get(1).getProductImageUrl());
    }

    // ---------- review moderation ----------

    @Test
    void flagReviewTrimsAndCapsTheReasonAndTreatsBlankAsNone() {
        service.flagReview(1, 2, "  spam  ");
        service.flagReview(1, 3, "   ");
        service.flagReview(1, 4, "x".repeat(500));

        verify(productClient).flagReview(1, 2, "spam");
        verify(productClient).flagReview(1, 3, null);
        verify(productClient).flagReview(1, 4, "x".repeat(200));
    }

    @Test
    void getFlaggedReviewsUsesTheServiceKeyAndSaneDefaults() {
        when(productClient.getFlaggedReviews(SERVICE_KEY, 0, 50)).thenReturn(new ModerationReviewsResult(List.of()));
        when(productClient.getFlaggedReviews(SERVICE_KEY, 0, 200)).thenReturn(new ModerationReviewsResult(List.of()));

        assertEquals(0, service.getFlaggedReviews(null, null).size());
        assertEquals(0, service.getFlaggedReviews(-3, 9999).size());
    }

    @Test
    void hideAndUnhideForwardTheProductAndReviewIdsWithTheServiceKey() {
        service.hideReview(7, 9);
        service.unhideReview(7, 9);

        verify(productClient).hideReview(SERVICE_KEY, 7, 9);
        verify(productClient).unhideReview(SERVICE_KEY, 7, 9);
    }

    // ---------- searchProducts ----------

    @Test
    void searchProductsDelegatesToTheProductClient() {
        when(productClient.search("mug", "home", 0, 200))
                .thenReturn(new ProductSearchResult(List.of(product(1, 9.99, 10))));

        List<Product> result = service.searchProducts("mug", "home");

        assertEquals(1, result.size());
    }

    // The storefront used to silently stop at the first 200 products - every page must now be fetched.
    @Test
    void searchProductsWalksEveryPageUntilTheLastOne() {
        when(productClient.search(null, null, 0, 200))
                .thenReturn(new ProductSearchResult(List.of(product(1, 9.99, 10)), false));
        when(productClient.search(null, null, 1, 200))
                .thenReturn(new ProductSearchResult(List.of(product(2, 9.99, 10)), false));
        when(productClient.search(null, null, 2, 200))
                .thenReturn(new ProductSearchResult(List.of(product(3, 9.99, 10)), true));

        List<Product> result = service.searchProducts(null, null);

        assertEquals(List.of(1, 2, 3), result.stream().map(Product::getProductId).toList());
    }

    // A downstream that never reports "last" but runs dry must not loop forever.
    @Test
    void searchProductsStopsOnAnEmptyPage() {
        when(productClient.search(null, null, 0, 200))
                .thenReturn(new ProductSearchResult(List.of(product(1, 9.99, 10)), false));
        when(productClient.search(null, null, 1, 200))
                .thenReturn(new ProductSearchResult(List.of(), false));

        assertEquals(1, service.searchProducts(null, null).size());
    }

    // Blank/empty search fields are normalized to null before reaching ProductService, so an empty text box
    // means "no filter" rather than a literal empty-string match.
    @Test
    void searchProductsTreatsBlankFiltersAsNoFilter() {
        when(productClient.search(null, null, 0, 200))
                .thenReturn(new ProductSearchResult(List.of(product(1, 9.99, 10))));

        List<Product> result = service.searchProducts("  ", "");

        assertEquals(1, result.size());
    }

    // ---------- getRatingSummaries ----------

    @Test
    void getRatingSummariesSkipsAProductWhoseLookupFails() {
        when(productClient.getRatingSummary(1)).thenReturn(new ProductRatingSummary(1, 4.5, 10));
        when(productClient.getRatingSummary(2)).thenThrow(declinedBy("rating-summary", 404, "Product not found"));

        List<ProductRatingSummary> result = service.getRatingSummaries(List.of(1, 2));

        assertEquals(1, result.size());
        assertEquals(4.5, result.get(0).averageRating());
    }

    // ---------- getProductReviews / addProductReview ----------

    @Test
    void getProductReviewsDefaultsPageAndSizeWhenNotProvided() {
        when(productClient.getReviews(any(), eq(1L), eq(0), eq(20)))
                .thenReturn(new ProductReviewsResult(List.of(new ProductReview(1, "Alice", 9876543210L, 5, "Great!", LocalDateTime.now()))));

        List<StorefrontReview> result = service.getProductReviews(1, null, null);

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).reviewerName());
    }

    @Test
    void getProductReviewsUsesProvidedPageAndSize() {
        when(productClient.getReviews(any(), eq(1L), eq(2), eq(5))).thenReturn(new ProductReviewsResult(List.of()));

        assertTrue(service.getProductReviews(1, 2, 5).isEmpty());
        verify(productClient).getReviews(any(), eq(1L), eq(2), eq(5));
    }

    @Test
    void getProductReviewsMarksOnlyRealKeptBuyersAsVerified() {
        long buyer = 9876543210L, cancelledBuyer = 9876543211L, pendingBuyer = 9876543212L,
                otherProductBuyer = 9876543213L, stranger = 9876543214L;
        when(productClient.getReviews(any(), eq(1L), eq(0), eq(20))).thenReturn(new ProductReviewsResult(List.of(
                new ProductReview(1, "Buyer", buyer, 5, "a", LocalDateTime.now()),
                new ProductReview(2, "Cancelled", cancelledBuyer, 5, "b", LocalDateTime.now()),
                new ProductReview(3, "Pending", pendingBuyer, 5, "c", LocalDateTime.now()),
                new ProductReview(4, "Other", otherProductBuyer, 5, "d", LocalDateTime.now()),
                new ProductReview(5, "Stranger", stranger, 5, "e", LocalDateTime.now()),
                new ProductReview(6, "Buyer again", buyer, 4, "f", LocalDateTime.now()))));
        Cart kept = cart(buyer, item(1, 1));
        kept.setStatus(OrderStatus.RETURNED);
        Cart cancelled = cart(cancelledBuyer, item(1, 1));
        cancelled.setStatus(OrderStatus.CANCELLED);
        Cart pending = cart(pendingBuyer, item(1, 1));
        pending.setStatus(OrderStatus.PENDING_PAYMENT);
        Cart otherProduct = cart(otherProductBuyer, item(2, 1));
        otherProduct.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findBycustomerPhno(buyer)).thenReturn(List.of(kept));
        when(orderRepository.findBycustomerPhno(cancelledBuyer)).thenReturn(List.of(cancelled));
        when(orderRepository.findBycustomerPhno(pendingBuyer)).thenReturn(List.of(pending));
        when(orderRepository.findBycustomerPhno(otherProductBuyer)).thenReturn(List.of(otherProduct));
        when(orderRepository.findBycustomerPhno(stranger)).thenReturn(List.of());

        List<StorefrontReview> result = service.getProductReviews(1, null, null);

        assertEquals(List.of(true, false, false, false, false, true),
                result.stream().map(StorefrontReview::verifiedPurchase).toList());
        verify(orderRepository, times(1)).findBycustomerPhno(buyer);
    }

    @Test
    void addProductReviewDelegatesToProductClientAndFlagsVerification() {
        ProductReview saved = new ProductReview(1, "Bob", 9876543210L, 4, "Good", LocalDateTime.now());
        when(productClient.addReview(eq(1L), any())).thenReturn(saved);
        Cart bought = cart(9876543210L, item(1, 1));
        bought.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findBycustomerPhno(9876543210L)).thenReturn(List.of(bought));

        StorefrontReview result = service.addProductReview(1, "Bob", 9876543210L, 4, "Good");

        assertEquals("Bob", result.reviewerName());
        assertTrue(result.verifiedPurchase());
        verify(productClient).addReview(1L, new ReviewSubmission("Bob", 9876543210L, 4, "Good"));
    }

    @Test
    void getGalleryImagesDelegatesToProductClient() {
        ProductGalleryImage image = new ProductGalleryImage(1, 1, "https://example.com/gallery1.jpg");
        when(productClient.getGalleryImages(1)).thenReturn(List.of(image));

        List<ProductGalleryImage> result = service.getGalleryImages(1);

        assertEquals(List.of(image), result);
    }

    @Test
    void getCouponsDelegatesToTheCouponRepository() {
        when(couponRepository.findAll()).thenReturn(List.of(coupon("SAVE10", 10, true)));
        assertEquals(1, service.getCoupons().size());
    }

    // ---------- getFrequentlyBoughtTogether ----------

    @Test
    void getFrequentlyBoughtTogetherRanksOtherProductsByCoOccurrenceCount() {
        Cart cartA = cart(CUSTOMER, item(1, 1), item(2, 1));
        Cart cartB = cart(CUSTOMER, item(1, 1), item(2, 1));
        Cart cartC = cart(CUSTOMER, item(1, 1), item(3, 1));
        when(orderRepository.findAll()).thenReturn(List.of(cartA, cartB, cartC));
        when(productClient.getProductById(2)).thenReturn(product(2, 20.0, 5));
        when(productClient.getProductById(3)).thenReturn(product(3, 30.0, 5));

        List<FrequentlyBoughtTogether> result = service.getFrequentlyBoughtTogether(1, null);

        assertEquals(2, result.size());
        assertEquals(2, result.get(0).productId());
        assertEquals(2, result.get(0).timesBoughtTogether());
        assertEquals(3, result.get(1).productId());
        assertEquals(1, result.get(1).timesBoughtTogether());
    }

    @Test
    void getFrequentlyBoughtTogetherExcludesCancelledOrders() {
        Cart cancelled = cart(CUSTOMER, item(1, 1), item(2, 1));
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findAll()).thenReturn(List.of(cancelled));

        assertTrue(service.getFrequentlyBoughtTogether(1, null).isEmpty());
        verifyNoInteractions(productClient);
    }

    @Test
    void getFrequentlyBoughtTogetherIgnoresOrdersThatDontContainTheQueriedProduct() {
        Cart cart = cart(CUSTOMER, item(2, 1), item(3, 1));
        when(orderRepository.findAll()).thenReturn(List.of(cart));

        assertTrue(service.getFrequentlyBoughtTogether(1, null).isEmpty());
    }

    @Test
    void getFrequentlyBoughtTogetherDefaultsToFiveAndCapsAtTwenty() {
        List<Cart> carts = new ArrayList<>();
        for (int productId = 2; productId <= 30; productId++) {
            carts.add(cart(CUSTOMER, item(1, 1), item(productId, 1)));
            lenient().when(productClient.getProductById(productId)).thenReturn(product(productId, 10.0, 5));
        }
        when(orderRepository.findAll()).thenReturn(carts);

        assertEquals(5, service.getFrequentlyBoughtTogether(1, null).size());
        assertEquals(5, service.getFrequentlyBoughtTogether(1, 5).size());
        assertEquals(20, service.getFrequentlyBoughtTogether(1, 1000).size());
    }

    // A product that's since been removed from the catalog must not blow up the whole list - it's simply
    // skipped, same reasoning getPriceDropAlerts() already applies.
    @Test
    void getFrequentlyBoughtTogetherSkipsAProductWhoseLookupFails() {
        Cart cart = cart(CUSTOMER, item(1, 1), item(2, 1));
        when(orderRepository.findAll()).thenReturn(List.of(cart));
        when(productClient.getProductById(2)).thenThrow(declinedBy("byId", 404, "Product not found"));

        assertTrue(service.getFrequentlyBoughtTogether(1, null).isEmpty());
    }

    // ---------- getAvailableCoupons ----------

    @Test
    void availableCouponsSkipsInactiveExpiredAndExhaustedAndSortsBestFirst() {
        Coupon fine = coupon("SAVE10", 10, true);
        Coupon better = coupon("BIG20", 20, true);
        Coupon inactive = coupon("OFF50", 50, false);
        Coupon expired = coupon("OLD30", 30, true);
        expired.setExpiryDate(Instant.now().minusSeconds(60));
        Coupon exhausted = coupon("FULL40", 40, true);
        exhausted.setMaxRedemptions(5);
        exhausted.setRedemptionCount(5);
        when(couponRepository.findAll()).thenReturn(List.of(fine, better, inactive, expired, exhausted));

        List<CouponSuggestion> result = service.getAvailableCoupons(CUSTOMER);

        assertEquals(List.of("BIG20", "SAVE10"), result.stream().map(CouponSuggestion::code).toList());
        assertNull(result.get(0).usesLeft());
    }

    @Test
    void availableCouponsHidesOneTheCustomerHasAlreadyUsedUpAndReportsUsesLeft() {
        Coupon used = coupon("ONCE", 10, true);
        used.setPerCustomerLimit(1);
        Coupon twice = coupon("TWICE", 15, true);
        twice.setPerCustomerLimit(2);
        CouponRedemption usedRedemption = new CouponRedemption();
        usedRedemption.setCount(1);
        when(couponRepository.findAll()).thenReturn(List.of(used, twice));
        when(couponRedemptionRepository.findByCouponCodeAndCustomerPhno("ONCE", CUSTOMER))
                .thenReturn(Optional.of(usedRedemption));
        when(couponRedemptionRepository.findByCouponCodeAndCustomerPhno("TWICE", CUSTOMER))
                .thenReturn(Optional.of(usedRedemption));

        List<CouponSuggestion> result = service.getAvailableCoupons(CUSTOMER);

        assertEquals(1, result.size());
        assertEquals("TWICE", result.get(0).code());
        assertEquals(1, result.get(0).usesLeft());
    }

    @Test
    void availableCouponsRejectsAnInvalidPhoneNumber() {
        assertThrows(RuntimeException.class, () -> service.getAvailableCoupons(123));
    }

    // ---------- searchOrders / exportOrdersCsv ----------

    private Cart adminOrder(long id, long phno, OrderStatus status, PaymentMethod method, String name) {
        Cart c = cart(phno, item(1, 2));
        c.setOrderId(id);
        c.setCustomerName(name);
        c.setStatus(status);
        c.setPaymentMethod(method);
        c.setTotalPrice(100.0);
        return c;
    }

    private TrackingEvent trackedAt(long orderId, String instant) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setTimestamp(Instant.parse(instant));
        return e;
    }

    @Test
    void searchOrdersFiltersByStatusMethodAndPhoneNewestFirst() {
        when(orderRepository.findAll()).thenReturn(List.of(
                adminOrder(1, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "A"),
                adminOrder(2, CUSTOMER, OrderStatus.DELIVERED, PaymentMethod.CASH, "B"),
                adminOrder(3, CUSTOMER, OrderStatus.PLACED, PaymentMethod.PHONEPE, "C"),
                adminOrder(4, CUSTOMER + 1, OrderStatus.PLACED, PaymentMethod.CASH, "D"),
                adminOrder(5, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "E")));
        when(trackingEventRepository.findAll()).thenReturn(List.of());

        List<AdminOrderRow> rows = service.searchOrders("placed", "cash", CUSTOMER, null, null);

        assertEquals(List.of(5L, 1L), rows.stream().map(AdminOrderRow::orderId).toList());
        assertEquals("1 x 2", rows.get(0).items());
    }

    @Test
    void searchOrdersDateRangeUsesFirstTrackingEventAndExcludesUndatedOrders() {
        when(orderRepository.findAll()).thenReturn(List.of(
                adminOrder(1, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "A"),
                adminOrder(2, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "B"),
                adminOrder(3, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "C"),
                adminOrder(4, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "D")));
        when(trackingEventRepository.findAll()).thenReturn(List.of(
                trackedAt(1, "2026-10-01T23:59:59Z"),
                trackedAt(1, "2026-10-05T00:00:00Z"),
                trackedAt(2, "2026-10-02T00:00:00Z"),
                trackedAt(3, "2026-10-03T23:59:59Z")));

        List<AdminOrderRow> rows = service.searchOrders(null, null, null,
                java.time.LocalDate.parse("2026-10-02"), java.time.LocalDate.parse("2026-10-03"));

        assertEquals(List.of(3L, 2L), rows.stream().map(AdminOrderRow::orderId).toList());
    }

    @Test
    void searchOrdersRejectsAnUnknownStatusOrPaymentMethod() {
        assertThrows(ProductException.class, () -> service.searchOrders("bogus", null, null, null, null));
        assertThrows(ProductException.class, () -> service.searchOrders(null, "bitcoin", null, null, null));
    }

    @Test
    void exportOrdersCsvQuotesCellsAndNeutralisesFormulas() {
        Cart tricky = adminOrder(7, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "Smith, \"Bob\"");
        Cart formula = adminOrder(8, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "=HYPERLINK(\"x\")");
        when(orderRepository.findAll()).thenReturn(List.of(tricky, formula));
        when(trackingEventRepository.findAll()).thenReturn(List.of());

        String csv = service.exportOrdersCsv(null, null, null, null, null);
        String[] lines = csv.split("\r\n");

        assertTrue(lines[0].startsWith("orderId,placedAt,customerName"));
        assertEquals(3, lines.length);
        assertTrue(lines[1].startsWith("8,,\"'=HYPERLINK(\"\"x\"\")\","));
        assertTrue(lines[2].startsWith("7,,\"Smith, \"\"Bob\"\"\","));
    }

    // ---------- delivery note ----------

    @Test
    void orderRejectsAnOverlongDeliveryNoteBeforeAnyPayment() {
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setDeliveryNote("x".repeat(201));

        assertThrows(ProductException.class, () -> service.order(cart, "Bearer t", "key"));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void orderTrimsTheDeliveryNoteAndTurnsABlankOneIntoNull() {
        Cart noted = cart(CUSTOMER, item(1, 1));
        noted.setPaymentMethod(PaymentMethod.CASH);
        noted.setDeliveryNote("  leave with security  ");
        Cart blank = cart(CUSTOMER, item(1, 1));
        blank.setPaymentMethod(PaymentMethod.CASH);
        blank.setDeliveryNote("   ");
        when(productClient.getProductById(1)).thenReturn(product(1, 10.0, 5));
        when(orderRepository.save(any(Cart.class))).thenAnswer(inv -> {
            Cart saved = inv.getArgument(0);
            saved.setOrderId(1L);
            return saved;
        });

        assertEquals("leave with security", service.order(noted, null, "k1").getDeliveryNote());
        assertNull(service.order(blank, null, "k2").getDeliveryNote());
    }

    @Test
    void exportOrdersCsvIncludesTheDeliveryNote() {
        Cart order = adminOrder(7, CUSTOMER, OrderStatus.PLACED, PaymentMethod.CASH, "Ann");
        order.setDeliveryNote("Call, don't ring");
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(trackingEventRepository.findAll()).thenReturn(List.of());

        String csv = service.exportOrdersCsv(null, null, null, null, null);

        assertTrue(csv.split("\r\n")[0].endsWith(",deliveryNote"));
        assertTrue(csv.split("\r\n")[1].endsWith(",\"Call, don't ring\""));
    }

    // ---------- getLowStockReport ----------

    @Test
    void lowStockReportListsOnlyProductsAtOrBelowTheirThresholdOutFirstWithWaitlistCounts() {
        Product plenty = product(1, 10.0, 50);
        Product low = product(2, 10.0, 3);
        Product out = product(3, 10.0, 0);
        Product boundary = product(4, 10.0, 5);
        Product customThreshold = product(5, 10.0, 8);
        customThreshold.setLowStockThreshold(10);
        when(productClient.findAll()).thenReturn(List.of(plenty, low, out, boundary, customThreshold));
        lenient().when(stockWaitlistRepository.countByProductId(anyInt())).thenReturn(0L);
        when(stockWaitlistRepository.countByProductId(3)).thenReturn(4L);

        List<LowStockItem> report = service.getLowStockReport();

        assertEquals(List.of(3, 2, 4, 5), report.stream().map(LowStockItem::productId).toList());
        assertEquals("OUT", report.get(0).level());
        assertEquals(4L, report.get(0).waitlistCount());
        assertEquals("LOW", report.get(1).level());
        assertEquals(0L, report.get(1).waitlistCount());
    }

    @Test
    void lowStockReportIsEmptyWhenEverythingIsWellStocked() {
        when(productClient.findAll()).thenReturn(List.of(product(1, 10.0, 50)));

        assertTrue(service.getLowStockReport().isEmpty());
    }

    // ---------- getSalesAnalytics ----------

    @Test
    void getSalesAnalyticsExcludesCancelledOrdersFromRevenueButCountsThemByStatus() {
        Cart placed = cart(CUSTOMER, item(1, 1));
        placed.setTotalPrice(100.0);
        placed.setStatus(OrderStatus.PLACED);
        Cart cancelled = cart(CUSTOMER, item(1, 1));
        cancelled.setTotalPrice(50.0);
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findAll()).thenReturn(List.of(placed, cancelled));
        when(productClient.getProductById(1)).thenReturn(product(1, 100.0, 5));

        SalesAnalytics result = service.getSalesAnalytics();

        assertEquals(2, result.totalOrders());
        assertEquals(100.0, result.totalRevenue());
        assertEquals(1L, result.ordersByStatus().get("PLACED"));
        assertEquals(1L, result.ordersByStatus().get("CANCELLED"));
    }

    @Test
    void getSalesAnalyticsGroupsRevenueByPaymentMethod() {
        Cart phonepeOrder = cart(CUSTOMER, item(1, 1));
        phonepeOrder.setTotalPrice(100.0);
        phonepeOrder.setPaymentMethod(PaymentMethod.PHONEPE);
        Cart cashOrder = cart(CUSTOMER, item(1, 1));
        cashOrder.setTotalPrice(50.0);
        cashOrder.setPaymentMethod(PaymentMethod.CASH);
        when(orderRepository.findAll()).thenReturn(List.of(phonepeOrder, cashOrder));
        when(productClient.getProductById(1)).thenReturn(product(1, 100.0, 5));

        SalesAnalytics result = service.getSalesAnalytics();

        assertEquals(100.0, result.revenueByPaymentMethod().get("PHONEPE"));
        assertEquals(50.0, result.revenueByPaymentMethod().get("CASH"));
    }

    // A handful of pre-existing dev-database rows predate the status/paymentMethod columns and can come back
    // null from a real query - grouped under "UNKNOWN" rather than throwing a NullPointerException.
    @Test
    void getSalesAnalyticsGroupsNullStatusAndPaymentMethodAsUnknown() {
        Cart legacyOrder = cart(CUSTOMER, item(1, 1));
        legacyOrder.setTotalPrice(20.0);
        legacyOrder.setStatus(null);
        legacyOrder.setPaymentMethod(null);
        when(orderRepository.findAll()).thenReturn(List.of(legacyOrder));
        when(productClient.getProductById(1)).thenReturn(product(1, 20.0, 5));

        SalesAnalytics result = service.getSalesAnalytics();

        assertEquals(1L, result.ordersByStatus().get("UNKNOWN"));
        assertEquals(20.0, result.revenueByPaymentMethod().get("UNKNOWN"));
    }

    @Test
    void getSalesAnalyticsRanksTopProductsByUnitsSold() {
        Cart cart = cart(CUSTOMER, item(1, 5), item(2, 1));
        cart.setTotalPrice(100.0);
        when(orderRepository.findAll()).thenReturn(List.of(cart));
        when(productClient.getProductById(1)).thenReturn(product(1, 10.0, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 20.0, 5));

        List<TopSellingProduct> topProducts = service.getSalesAnalytics().topProducts();

        assertEquals(2, topProducts.size());
        assertEquals(1, topProducts.get(0).productId());
        assertEquals(5, topProducts.get(0).unitsSold());
        assertEquals(50.0, topProducts.get(0).revenue());
        assertEquals(2, topProducts.get(1).productId());
    }

    // ---------- shipping addresses ----------

    private ShippingAddress address(long phno, boolean isDefault) {
        ShippingAddress a = new ShippingAddress();
        a.setCustomerPhno(phno);
        a.setLine1("221B Baker Street");
        a.setCity("London");
        a.setState("Greater London");
        a.setPincode("110001");
        a.setDefault(isDefault);
        return a;
    }

    @Test
    void saveAddressCannotOverwriteAnotherCustomersAddress() {
        ShippingAddress victim = new ShippingAddress();
        victim.setId(7L);
        victim.setCustomerPhno(9000000001L);
        when(shippingAddressRepository.findById(7L)).thenReturn(Optional.of(victim));
        ShippingAddress attack = new ShippingAddress();
        attack.setId(7L);
        attack.setCustomerPhno(CUSTOMER);
        attack.setLine1("x");
        attack.setCity("c");
        attack.setState("s");
        attack.setPincode("560001");

        assertThrows(OrderNotFoundException.class, () -> service.saveAddress(attack));
        verify(shippingAddressRepository, never()).save(any());
    }

    @Test
    void saveAddressRejectsAnInvalidPhoneNumber() {
        ShippingAddress a = address(555, false);
        assertThrows(ProductException.class, () -> service.saveAddress(a));
        verify(shippingAddressRepository, never()).save(any());
    }

    @Test
    void saveAddressRejectsAMissingRequiredField() {
        ShippingAddress a = address(CUSTOMER, false);
        a.setCity(" ");
        assertThrows(ProductException.class, () -> service.saveAddress(a));
        verify(shippingAddressRepository, never()).save(any());
    }

    @Test
    void saveAddressSavesANonDefaultAddressWithoutTouchingExistingDefaults() {
        ShippingAddress a = address(CUSTOMER, false);
        when(shippingAddressRepository.save(a)).thenReturn(a);

        service.saveAddress(a);

        verify(shippingAddressRepository, never()).findByCustomerPhnoAndIsDefaultTrue(anyLong());
        verify(shippingAddressRepository).save(a);
    }

    // Making a new address the default must un-default whichever address previously held it - a customer can
    // never end up with two defaults at once.
    @Test
    void saveAddressUnsetsThePreviousDefaultWhenSavingANewDefault() {
        ShippingAddress oldDefault = address(CUSTOMER, true);
        oldDefault.setId(1L);
        when(shippingAddressRepository.findByCustomerPhnoAndIsDefaultTrue(CUSTOMER)).thenReturn(List.of(oldDefault));
        when(shippingAddressRepository.save(oldDefault)).thenReturn(oldDefault);
        ShippingAddress newDefault = address(CUSTOMER, true);
        newDefault.setId(2L);
        when(shippingAddressRepository.findById(2L)).thenReturn(Optional.of(newDefault));
        when(shippingAddressRepository.save(newDefault)).thenReturn(newDefault);

        service.saveAddress(newDefault);

        assertFalse(oldDefault.isDefault());
        verify(shippingAddressRepository).save(oldDefault);
        verify(shippingAddressRepository).save(newDefault);
    }

    @Test
    void getAddressesRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getAddresses(555));
    }

    @Test
    void getAddressesDelegatesToTheRepository() {
        when(shippingAddressRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(address(CUSTOMER, false)));
        assertEquals(1, service.getAddresses(CUSTOMER).size());
    }

    @Test
    void deleteAddressThrowsWhenTheAddressDoesNotExist() {
        when(shippingAddressRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.deleteAddress(CUSTOMER, 99L));
        verify(shippingAddressRepository, never()).deleteById(any());
    }

    @Test
    void deleteAddressThrowsWhenTheAddressBelongsToAnotherCustomer() {
        ShippingAddress a = address(1111111111L, false);
        a.setId(99L);
        when(shippingAddressRepository.findById(99L)).thenReturn(Optional.of(a));

        assertThrows(OrderNotFoundException.class, () -> service.deleteAddress(CUSTOMER, 99L));
        verify(shippingAddressRepository, never()).deleteById(any());
    }

    @Test
    void deleteAddressRemovesTheOwnersOwnAddress() {
        ShippingAddress a = address(CUSTOMER, false);
        a.setId(99L);
        when(shippingAddressRepository.findById(99L)).thenReturn(Optional.of(a));

        service.deleteAddress(CUSTOMER, 99L);

        verify(shippingAddressRepository).deleteById(99L);
    }

    // ---------- loyalty account management ----------

    @Test
    void getLoyaltyAccountRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getLoyaltyAccount(555));
    }

    // A customer who's never earned anything still gets a zero-balance account back, not a 404 - same
    // first-class-empty-state approach as an empty wishlist or address list.
    @Test
    void getLoyaltyAccountReturnsAZeroBalanceForACustomerWithNoAccountYet() {
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.empty());

        LoyaltyAccount result = service.getLoyaltyAccount(CUSTOMER);

        assertEquals(0, result.getPointsBalance());
        assertEquals(CUSTOMER, result.getCustomerPhno());
        verify(loyaltyAccountRepository, never()).save(any());
    }

    @Test
    void getLoyaltyAccountReturnsTheExistingBalance() {
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 30)));
        assertEquals(30, service.getLoyaltyAccount(CUSTOMER).getPointsBalance());
    }

    // ---------- loyalty points expiry ----------

    @Test
    void getLoyaltyAccountExpiresABalanceWithNoRecentActivity() {
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        account.setLastActivityAt(Instant.now().minus(400, ChronoUnit.DAYS));
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        LoyaltyAccount result = service.getLoyaltyAccount(CUSTOMER);

        assertEquals(0, result.getPointsBalance());
        verify(loyaltyAccountRepository).save(account);
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(-100, captor.getValue().getPoints());
        assertEquals(LoyaltyTransactionType.EXPIRED, captor.getValue().getType());
    }

    @Test
    void getLoyaltyAccountDoesNotExpireABalanceWithRecentActivity() {
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        account.setLastActivityAt(Instant.now().minus(10, ChronoUnit.DAYS));
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        assertEquals(100, service.getLoyaltyAccount(CUSTOMER).getPointsBalance());
        verify(loyaltyAccountRepository, never()).save(any());
    }

    @Test
    void getLoyaltyAccountDoesNotExpireAnAccountThatHasNeverHadActivity() {
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        assertEquals(100, service.getLoyaltyAccount(CUSTOMER).getPointsBalance());
        verifyNoInteractions(loyaltyTransactionRepository);
    }

    @Test
    void getLoyaltyAccountDoesNotExpireAnAlreadyZeroBalance() {
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 0);
        account.setLastActivityAt(Instant.now().minus(400, ChronoUnit.DAYS));
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        service.getLoyaltyAccount(CUSTOMER);

        verify(loyaltyAccountRepository, never()).save(any());
        verifyNoInteractions(loyaltyTransactionRepository);
    }

    // Expiry is applied before the balance check, so redeeming against a stale (now-expired) balance fails the
    // same way redeeming against an insufficient balance always has.
    @Test
    void orderRejectsRedemptionAgainstAnExpiredBalance() {
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        account.setLastActivityAt(Instant.now().minus(400, ChronoUnit.DAYS));
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));
        Cart cart = cart(CUSTOMER, item(1, 1));
        cart.setPointsRedeemed(50);

        assertThrows(ProductException.class, () -> service.order(cart, AUTH, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void earnLoyaltyPointsRefreshesLastActivityAt() {
        Cart cart = placedOrder(42L, 100000L, item(1, 1));
        cart.setStatus(OrderStatus.SHIPPED);
        cart.setTotalPrice(100.0);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 0);
        account.setLastActivityAt(Instant.now().minus(400, ChronoUnit.DAYS));
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        service.deliver(42L);

        assertTrue(account.getLastActivityAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    @Test
    void getLoyaltyHistoryRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getLoyaltyHistory(555));
    }

    @Test
    void getLoyaltyHistoryDelegatesToTheRepository() {
        LoyaltyTransaction tx = new LoyaltyTransaction();
        tx.setCustomerPhno(CUSTOMER);
        when(loyaltyTransactionRepository.findByCustomerPhnoOrderByTimestampDesc(CUSTOMER)).thenReturn(List.of(tx));
        assertEquals(List.of(tx), service.getLoyaltyHistory(CUSTOMER));
    }

    @Test
    void adjustLoyaltyPointsRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.adjustLoyaltyPoints(555, 10, "goodwill"));
    }

    @Test
    void adjustLoyaltyPointsRejectsABlankReason() {
        assertThrows(ProductException.class, () -> service.adjustLoyaltyPoints(CUSTOMER, 10, " "));
        verifyNoInteractions(loyaltyAccountRepository);
    }

    @Test
    void adjustLoyaltyPointsRejectsADebitThatWouldGoNegative() {
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(loyaltyAccount(CUSTOMER, 5)));
        assertThrows(ProductException.class, () -> service.adjustLoyaltyPoints(CUSTOMER, -10, "correction"));
        verify(loyaltyAccountRepository, never()).save(any());
    }

    @Test
    void adjustLoyaltyPointsCreditsANewAccountAndRecordsTheAdjustment() {
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.empty());
        when(loyaltyAccountRepository.save(any(LoyaltyAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        LoyaltyAccount result = service.adjustLoyaltyPoints(CUSTOMER, 25, "goodwill credit");

        assertEquals(25, result.getPointsBalance());
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(25, captor.getValue().getPoints());
        assertEquals(LoyaltyTransactionType.ADJUSTED, captor.getValue().getType());
        assertEquals("goodwill credit", captor.getValue().getReason());
        assertNull(captor.getValue().getOrderId());
    }

    // A goodwill credit isn't spending, so it must not let someone game their way into a higher tier - only
    // pointsBalance moves, lifetimePointsEarned (the sole basis for tier) stays put.
    @Test
    void adjustLoyaltyPointsDoesNotCountTowardTierProgress() {
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 0);
        account.setLifetimePointsEarned(100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));
        when(loyaltyAccountRepository.save(account)).thenReturn(account);

        LoyaltyAccount result = service.adjustLoyaltyPoints(CUSTOMER, 1000, "goodwill credit");

        assertEquals(1000, result.getPointsBalance());
        assertEquals(100, result.getLifetimePointsEarned());
        assertEquals(LoyaltyTier.BRONZE, result.getTier());
    }

    // ---------- getCustomerProfile ----------

    @Test
    void getCustomerProfileRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getCustomerProfile(555));
    }

    @Test
    void getCustomerProfileRollsUpEveryFigure() {
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cart(CUSTOMER), cart(CUSTOMER)));
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of(new Wishlist()));
        when(productClient.getReviewCount(CUSTOMER)).thenReturn(4L);
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 60);
        account.setLifetimePointsEarned(600);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        CustomerProfile result = service.getCustomerProfile(CUSTOMER);

        assertEquals(CUSTOMER, result.customerPhno());
        assertEquals(2, result.totalOrders());
        assertEquals(1, result.wishlistCount());
        assertEquals(4L, result.reviewCount());
        assertEquals(60, result.loyaltyPointsBalance());
        assertEquals(600, result.lifetimePointsEarned());
        assertEquals(LoyaltyTier.SILVER, result.loyaltyTier());
    }

    // ProductService being unreachable must not fail the whole profile - the review count just degrades to 0,
    // same "skip, don't blow up" reasoning as getPriceDropAlerts()/getFrequentlyBoughtTogether().
    @Test
    void getCustomerProfileDegradesReviewCountToZeroWhenProductServiceIsUnreachable() {
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of());
        when(wishlistRepository.findByCustomerPhno(CUSTOMER)).thenReturn(List.of());
        when(productClient.getReviewCount(CUSTOMER)).thenThrow(declinedBy("count", 503, "unreachable"));

        CustomerProfile result = service.getCustomerProfile(CUSTOMER);

        assertEquals(0, result.reviewCount());
    }

    // ---------- per-item cancel / return ----------

    private OrderItem pricedItem(int productId, int quantity, double unitPrice) {
        OrderItem i = item(productId, quantity);
        i.setUnitPrice(unitPrice);
        return i;
    }

    private OrderItem itemOf(Cart cart, int productId) {
        return cart.getOrderItems().stream().filter(i -> i.getProductId() == productId).findFirst().orElseThrow();
    }

    @Test
    void orderRecordsWhatEachUnitCostAtCheckout() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 499.0, 10));
        Cart cart = cart(CUSTOMER, item(1, 2));
        cart.setPaymentMethod(PaymentMethod.CASH);

        Cart result = service.order(cart, null, null);

        assertEquals(499.0, result.getOrderItems().get(0).getUnitPrice());
    }

    // Gross 250 (2 x 100 + 1 x 50), paid 225 after a coupon: one unit of the 100 item is 40% of the order, so it
    // refunds 40% of what was actually paid - 90, not its 100 list price.
    @Test
    void cancelItemRefundsTheItemsShareOfWhatWasActuallyPaid() {
        Cart cart = placedOrder(42L, 100000L, pricedItem(1, 2, 100), pricedItem(2, 1, 50));
        cart.setTotalPrice(225);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.cancelItem(42L, 1, 1, AUTH, "item-1", null, null);

        verify(phonepeClient).refund(AUTH, 100000L, new RefundRequest("item-1", new BigDecimal("90.00")));
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
        assertEquals(1, itemOf(result, 1).getCancelledQuantity());
        assertEquals(1, itemOf(result, 1).getOutstandingQuantity());
        assertEquals(90.0, result.getRefundedAmount());
        assertEquals(OrderStatus.PLACED, result.getStatus());
        verify(trackingEventRepository, never()).save(any());
    }

    // Three equal items of a 20.00 order: 6.67 + 6.67 would leave 6.66, and the last cancel must take exactly that,
    // so nothing is left stuck and nothing extra is refunded.
    @Test
    void cancellingTheLastUnitsRefundsExactlyWhatIsLeftAndCancelsTheOrder() {
        Cart cart = placedOrder(42L, 100000L, pricedItem(1, 1, 10), pricedItem(2, 1, 10), pricedItem(3, 1, 10));
        cart.setTotalPrice(20);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        service.cancelItem(42L, 1, 1, AUTH, null, null, null);
        service.cancelItem(42L, 2, 1, AUTH, null, null, null);
        Cart result = service.cancelItem(42L, 3, 1, AUTH, null, null, null);

        ArgumentCaptor<RefundRequest> refunds = ArgumentCaptor.forClass(RefundRequest.class);
        verify(phonepeClient, times(3)).refund(eq(AUTH), eq(100000L), refunds.capture());
        assertEquals(List.of(new BigDecimal("6.67"), new BigDecimal("6.67"), new BigDecimal("6.66")),
                refunds.getAllValues().stream().map(RefundRequest::amount).toList());
        assertEquals(20.0, result.getRefundedAmount());
        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        ArgumentCaptor<TrackingEvent> tracking = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(trackingEventRepository).save(tracking.capture());
        assertEquals(OrderStatus.CANCELLED, tracking.getValue().getStatus());
    }

    @Test
    void cancelItemOfACashOrderJustReducesWhatIsDue() {
        Cart cart = placedOrder(42L, 0L, pricedItem(1, 2, 100));
        cart.setPaymentTransactionId(null);
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setTotalPrice(200);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.cancelItem(42L, 1, 1, null, null, null, null);

        verifyNoInteractions(phonepeClient);
        assertEquals(100.0, result.getRefundedAmount());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
    }

    @Test
    void cancelItemRejectsMoreThanIsStillOutstanding() {
        OrderItem item = pricedItem(1, 2, 100);
        item.setCancelledQuantity(1);
        Cart cart = placedOrder(42L, 100000L, item);
        cart.setTotalPrice(200);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        ProductException ex = assertThrows(ProductException.class, () -> service.cancelItem(42L, 1, 2, AUTH, null, null, null));

        assertEquals("Only 1 of that item can still be changed", ex.getMessage());
        verifyNoInteractions(phonepeClient);
        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
    }

    @Test
    void cancelItemRejectsAnOrderPlacedBeforeUnitPricesWereRecorded() {
        Cart cart = placedOrder(42L, 100000L, item(1, 2));
        cart.setTotalPrice(200);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.cancelItem(42L, 1, 1, AUTH, null, null, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void cancelItemRejectsAProductThatIsNotInTheOrder() {
        Cart cart = placedOrder(42L, 100000L, pricedItem(1, 2, 100));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.cancelItem(42L, 9, 1, AUTH, null, null, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void wholeCancelAfterAPartialOneRestocksOnlyWhatIsLeftAndRefundsTheRemainder() {
        OrderItem partlyCancelled = pricedItem(1, 3, 100);
        partlyCancelled.setCancelledQuantity(1);
        Cart cart = placedOrder(42L, 100000L, partlyCancelled);
        cart.setTotalPrice(300);
        cart.setRefundedAmount(100);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);

        Cart result = service.cancel(42L, AUTH, null);

        // no amount: PhonepayService refunds whatever is still unrefunded (the remaining 200)
        verify(phonepeClient).refund(AUTH, 100000L, new RefundRequest(null, null));
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
        assertEquals(300.0, result.getRefundedAmount());
        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        assertEquals(3, itemOf(result, 1).getCancelledQuantity());
        assertEquals(0, itemOf(result, 1).getOutstandingQuantity());
    }

    @Test
    void returnItemRefundsTheShareAndClawsBackThatShareOfTheEarnedPoints() {
        Cart cart = deliveredOrder(42L, 100000L, pricedItem(1, 1, 100), pricedItem(2, 1, 100));
        cart.setTotalPrice(200);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyTransactionRepository.findByOrderIdAndType(42L, LoyaltyTransactionType.EARNED))
                .thenReturn(Optional.of(earnedTransaction(42L, 20)));
        when(loyaltyTransactionRepository.findAllByOrderIdAndType(42L, LoyaltyTransactionType.ADJUSTED)).thenReturn(List.of());
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        Cart result = service.returnItem(42L, 2, 1, "wrong size", AUTH, null, null, null);

        verify(phonepeClient).refund(AUTH, 100000L, new RefundRequest(null, new BigDecimal("100.00")));
        assertEquals(1, itemOf(result, 2).getReturnedQuantity());
        assertEquals("wrong size", itemOf(result, 2).getReturnReason());
        assertEquals(OrderStatus.DELIVERED, result.getStatus());
        assertEquals(90, account.getPointsBalance());
        ArgumentCaptor<LoyaltyTransaction> captor = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(loyaltyTransactionRepository).save(captor.capture());
        assertEquals(-10, captor.getValue().getPoints());
    }

    // The earlier per-item return already clawed back 10 of the 20 earned - the full return takes only the other 10.
    @Test
    void wholeReturnAfterAPartialOneClawsBackOnlyThePointsNotAlreadyTaken() {
        OrderItem returned = pricedItem(2, 1, 100);
        returned.setReturnedQuantity(1);
        Cart cart = deliveredOrder(42L, 100000L, pricedItem(1, 1, 100), returned);
        cart.setTotalPrice(200);
        cart.setRefundedAmount(100);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));
        when(trackingEventRepository.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(phonepeClient.refund(eq(AUTH), eq(100000L), any(RefundRequest.class))).thenReturn(paymentResponse(100001));
        when(orderRepository.save(cart)).thenReturn(cart);
        when(loyaltyTransactionRepository.findByOrderIdAndType(42L, LoyaltyTransactionType.EARNED))
                .thenReturn(Optional.of(earnedTransaction(42L, 20)));
        LoyaltyTransaction earlierClawback = new LoyaltyTransaction();
        earlierClawback.setPoints(-10);
        when(loyaltyTransactionRepository.findAllByOrderIdAndType(42L, LoyaltyTransactionType.ADJUSTED))
                .thenReturn(List.of(earlierClawback));
        LoyaltyAccount account = loyaltyAccount(CUSTOMER, 100);
        when(loyaltyAccountRepository.findById(CUSTOMER)).thenReturn(Optional.of(account));

        Cart result = service.returnOrder(42L, AUTH, null, "changed my mind");

        verify(productClient).updateProductStock(SERVICE_KEY, 1, 1);
        verify(productClient, never()).updateProductStock(SERVICE_KEY, 2, 1);
        assertEquals(90, account.getPointsBalance());
        assertEquals(200.0, result.getRefundedAmount());
        assertEquals(OrderStatus.RETURNED, result.getStatus());
        assertEquals(1, itemOf(result, 1).getReturnedQuantity());
        assertEquals(1, itemOf(result, 2).getReturnedQuantity());
        assertEquals(0, itemOf(result, 1).getOutstandingQuantity());
    }

    @Test
    void returnItemOfAnOrderThatIsNotDeliveredIsRejected() {
        Cart cart = placedOrder(42L, 100000L, pricedItem(1, 1, 100));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(cart));

        assertThrows(ProductException.class, () -> service.returnItem(42L, 1, 1, "damaged", AUTH, null, null, null));
        verifyNoInteractions(phonepeClient);
    }

    @Test
    void returnItemNeedsAReason() {
        assertThrows(ProductException.class, () -> service.returnItem(42L, 1, 1, " ", AUTH, null, null, null));
        verifyNoInteractions(orderRepository, phonepeClient);
    }

    @Test
    void getSalesAnalyticsCountsRevenueNetOfRefunds() {
        Cart partlyRefunded = cart(CUSTOMER, pricedItem(1, 2, 50));
        partlyRefunded.setTotalPrice(100.0);
        partlyRefunded.setRefundedAmount(50.0);
        partlyRefunded.getOrderItems().get(0).setCancelledQuantity(1);
        partlyRefunded.setStatus(OrderStatus.PLACED);
        Cart returned = cart(CUSTOMER, pricedItem(1, 1, 50));
        returned.setTotalPrice(50.0);
        returned.setStatus(OrderStatus.RETURNED);
        when(orderRepository.findAll()).thenReturn(List.of(partlyRefunded, returned));
        when(productClient.getProductById(1)).thenReturn(product(1, 50.0, 5));

        SalesAnalytics result = service.getSalesAnalytics();

        assertEquals(50.0, result.totalRevenue());
        assertEquals(1, result.topProducts().get(0).unitsSold());
    }

    // ---------- getRevenueTimeseries ----------

    private Cart dated(long orderId, double total, OrderStatus status) {
        Cart c = cart(CUSTOMER, item(1, 1));
        c.setOrderId(orderId);
        c.setTotalPrice(total);
        c.setStatus(status);
        return c;
    }

    private TrackingEvent placedEvent(long orderId, String instant) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(OrderStatus.PLACED);
        e.setTimestamp(Instant.parse(instant));
        return e;
    }

    @Test
    void revenueTimeseriesFillsEveryDayAndCountsRevenueNetOfRefunds() {
        Cart partlyRefunded = dated(1L, 100, OrderStatus.PLACED);
        partlyRefunded.setRefundedAmount(30);
        Cart sameDay = dated(2L, 50, OrderStatus.DELIVERED);
        Cart cancelled = dated(3L, 999, OrderStatus.CANCELLED);
        Cart returned = dated(4L, 70, OrderStatus.RETURNED);
        Cart noHistory = dated(5L, 10, OrderStatus.PLACED);
        when(orderRepository.findAll()).thenReturn(List.of(partlyRefunded, sameDay, cancelled, returned, noHistory));
        when(trackingEventRepository.findAll()).thenReturn(List.of(
                placedEvent(1L, "2026-10-02T10:00:00Z"), placedEvent(2L, "2026-10-02T12:00:00Z"),
                placedEvent(3L, "2026-10-02T12:00:00Z"), placedEvent(4L, "2026-10-04T09:00:00Z")));

        RevenueTimeseries result = service.getRevenueTimeseries(
                java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2026-10-04"), null, null);

        assertEquals(4, result.points().size());
        assertEquals(new RevenueTimeseries.Point(java.time.LocalDate.parse("2026-10-01"), 0, 0.0), result.points().get(0));
        assertEquals(new RevenueTimeseries.Point(java.time.LocalDate.parse("2026-10-02"), 2, 120.0), result.points().get(1));
        // a returned order still happened (counted) but is no longer revenue
        assertEquals(new RevenueTimeseries.Point(java.time.LocalDate.parse("2026-10-04"), 1, 0.0), result.points().get(3));
        assertEquals(120.0, result.totalRevenue());
        assertEquals(3, result.totalOrders());
        assertEquals(1, result.undatedOrders());
    }

    // 2026-10-01T20:00Z is already 2 October in India - the day follows the requested zone.
    @Test
    void revenueTimeseriesUsesTheRequestedTimeZoneForDayBoundaries() {
        when(orderRepository.findAll()).thenReturn(List.of(dated(1L, 100, OrderStatus.PLACED)));
        when(trackingEventRepository.findAll()).thenReturn(List.of(placedEvent(1L, "2026-10-01T20:00:00Z")));
        java.time.LocalDate from = java.time.LocalDate.parse("2026-10-01");
        java.time.LocalDate to = java.time.LocalDate.parse("2026-10-02");

        RevenueTimeseries utc = service.getRevenueTimeseries(from, to, "day", "UTC");
        RevenueTimeseries india = service.getRevenueTimeseries(from, to, "day", "Asia/Kolkata");

        assertEquals(100.0, utc.points().get(0).revenue());
        assertEquals(100.0, india.points().get(1).revenue());
        assertEquals("Asia/Kolkata", india.zone());
    }

    @Test
    void revenueTimeseriesGroupsByWeekStartingMonday() {
        when(orderRepository.findAll()).thenReturn(List.of(dated(1L, 10, OrderStatus.PLACED), dated(2L, 20, OrderStatus.PLACED)));
        // 2026-10-07 is a Wednesday, 2026-10-11 the Sunday of the same week
        when(trackingEventRepository.findAll()).thenReturn(List.of(
                placedEvent(1L, "2026-10-07T10:00:00Z"), placedEvent(2L, "2026-10-11T10:00:00Z")));

        RevenueTimeseries result = service.getRevenueTimeseries(
                java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2026-10-12"), "week", null);

        assertEquals(List.of(java.time.LocalDate.parse("2026-09-28"), java.time.LocalDate.parse("2026-10-05"),
                java.time.LocalDate.parse("2026-10-12")), result.points().stream().map(RevenueTimeseries.Point::periodStart).toList());
        assertEquals(new RevenueTimeseries.Point(java.time.LocalDate.parse("2026-10-05"), 2, 30.0), result.points().get(1));
    }

    @Test
    void revenueTimeseriesRejectsBadInput() {
        java.time.LocalDate day = java.time.LocalDate.parse("2026-10-01");
        assertThrows(ProductException.class, () -> service.getRevenueTimeseries(day, day.minusDays(1), null, null));
        assertThrows(ProductException.class, () -> service.getRevenueTimeseries(day, day.plusDays(400), null, null));
        assertThrows(ProductException.class, () -> service.getRevenueTimeseries(day, day, "month", null));
        assertThrows(ProductException.class, () -> service.getRevenueTimeseries(day, day, null, "Mars/Olympus"));
    }

    @Test
    void revenueTimeseriesDefaultsToTheLast30Days() {
        when(orderRepository.findAll()).thenReturn(List.of());
        when(trackingEventRepository.findAll()).thenReturn(List.of());

        RevenueTimeseries result = service.getRevenueTimeseries(null, null, null, null);

        assertEquals(30, result.points().size());
        assertEquals("day", result.bucket());
    }
}
