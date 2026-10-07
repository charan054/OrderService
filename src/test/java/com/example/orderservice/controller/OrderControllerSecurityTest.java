package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ProductReviewsResult;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsing the catalog is public; one customer's own data needs that customer's signed-in session
 * (X-Customer-Token) or the X-Service-Key; everything else (listing EVERY customer's orders, ship/deliver, ...)
 * needs the X-Service-Key. Real SecurityFilterChain, real (in-memory)
 * database - ProductClient (the Feign call to the real ProductService) and OrderKafkaProducer are stubbed out
 * (the latter only to keep the test fast: a real KafkaTemplate blocks for up to max.block.ms trying to reach a
 * broker that isn't running here - see OrderServiceTest for the actual notification behavior coverage).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerSecurityTest {

    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final String WRONG_KEY = "not-the-right-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerAuthService customerAuthService;

    @Autowired
    private CartRepository cartRepository;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private PhonepeClient phonepeClient;

    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private static final String NEW_ORDER = """
            {"customerName":"Buyer","customerPhno":9876543210,"orderItems":[{"productId":1,"productQuantity":1}]}
            """;

    private static final long CUSTOMER = 9876543210L;
    private static final long OTHER_CUSTOMER = 9123456789L;

    // A real signed-in storefront session for CUSTOMER (the phone number the fixtures below use).
    private RequestPostProcessor customer() {
        return sessionOf(CUSTOMER);
    }

    private RequestPostProcessor sessionOf(long phno) {
        String token = customerAuthService.issueSession(phno).token();
        return request -> {
            request.addHeader("X-Customer-Token", token);
            return request;
        };
    }

    @Test
    void catalogBrowsingIsPublic() throws Exception {
        when(productClient.findAll()).thenReturn(List.of());
        mockMvc.perform(get("/cart/display")).andExpect(status().isOk());
    }

    // Regression: the static dashboard was 401ing before Spring Security's static-resource handler ever got to
    // serve it, because nothing explicitly permitted it.
    @Test
    void customerProfileWithOwnSessionSucceeds() throws Exception {
        when(productClient.getReviewCount(9876543210L)).thenReturn(0L);
        mockMvc.perform(get("/customer/profile").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void frequentlyBoughtTogetherIsPublic() throws Exception {
        mockMvc.perform(get("/cart/frequentlyboughttogether").param("productId", "1")).andExpect(status().isOk());
    }

    @Test
    void searchIsPublic() throws Exception {
        when(productClient.search(any(), any(), anyInt(), anyInt())).thenReturn(new ProductSearchResult(List.of()));
        mockMvc.perform(get("/cart/search").param("name", "mug")).andExpect(status().isOk());
    }

    @Test
    void ratingsIsPublic() throws Exception {
        when(productClient.getRatingSummary(1)).thenReturn(new ProductRatingSummary(1, 4.5, 3));
        mockMvc.perform(get("/cart/ratings").param("productIds", "1")).andExpect(status().isOk());
    }

    @Test
    void reviewsListingIsPublic() throws Exception {
        when(productClient.getReviews(any(), eq(1L), eq(0), eq(20))).thenReturn(new ProductReviewsResult(List.of()));
        mockMvc.perform(get("/cart/reviews").param("productId", "1")).andExpect(status().isOk());
    }

    @Test
    void postingAReviewWithOwnSessionSucceeds() throws Exception {
        when(productClient.addReview(eq(1L), any()))
                .thenReturn(new ProductReview(1, "Alice", 9876543210L, 5, "Great!", LocalDateTime.now()));
        mockMvc.perform(post("/cart/reviews").param("productId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName":"Alice","reviewerPhno":9876543210,"rating":5,"comment":"Great!"}
                                """).with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void staticDashboardIsPublic() throws Exception {
        mockMvc.perform(get("/cart.html")).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnOrdersWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnWishlistWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/wishlist/byphno").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    // Backs the storefront's "My notifications" panel - same public trust level as byphno above.
    @Test
    void lookingUpOwnNotificationsWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/cart/notifications").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    // Straight proxy to ProductService's own public gallery listing - same catalog-browsing trust level as
    // /cart/display.
    @Test
    void productGalleryIsPublic() throws Exception {
        mockMvc.perform(get("/cart/gallery").param("productId", "1")).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnPriceDropAlertsWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/wishlist/pricedrops").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void addToWishlistWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/wishlist/add").param("phno", "9876543210").param("productId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addToWishlistWithValidKeySucceeds() throws Exception {
        Product widget = new Product();
        widget.setProductId(1);
        widget.setProductPrice(9.99);
        widget.setProductStock(10);
        when(productClient.getProductById(1)).thenReturn(widget);

        mockMvc.perform(post("/wishlist/add").param("phno", "9876543210").param("productId", "1")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void removeFromWishlistWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/wishlist/remove").param("phno", "9876543210").param("productId", "1"))
                .andExpect(status().isUnauthorized());
    }

    // The storefront's own wishlist add/remove - public, no X-Service-Key, same self-service trust level as
    // GET /wishlist/byphno (see SecurityConfig).
    @Test
    void addToOwnWishlistWithOwnSessionSucceeds() throws Exception {
        Product widget = new Product();
        widget.setProductId(1);
        widget.setProductPrice(9.99);
        widget.setProductStock(10);
        when(productClient.getProductById(1)).thenReturn(widget);

        mockMvc.perform(post("/wishlist/self/add").param("phno", "9876543210").param("productId", "1").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void removeFromOwnWishlistWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(delete("/wishlist/self/remove").param("phno", "9876543210").param("productId", "1").with(customer()))
                .andExpect(status().isOk());
    }

    // The back-in-stock waitlist is entirely self-service - no admin/X-Service-Key pair exists for it (unlike
    // Wishlist), same public trust level as /wishlist/self/add and /wishlist/byphno.
    @Test
    void addToOwnWaitlistWithOwnSessionSucceeds() throws Exception {
        Product widget = new Product();
        widget.setProductId(1);
        widget.setProductPrice(9.99);
        widget.setProductStock(0);
        when(productClient.getProductById(1)).thenReturn(widget);

        mockMvc.perform(post("/waitlist/self/add").param("phno", "9876543210").param("productId", "1").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnWaitlistWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/waitlist/byphno").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void removeFromOwnWaitlistWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(delete("/waitlist/self/remove").param("phno", "9876543210").param("productId", "1").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void addOrderWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addOrderWithWrongKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER)
                        .header("X-Service-Key", WRONG_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addOrderWithValidKeySucceeds() throws Exception {
        Product widget = new Product();
        widget.setProductId(1);
        widget.setProductPrice(9.99);
        widget.setProductStock(10);
        when(productClient.getProductById(anyInt())).thenReturn(widget);
        when(phonepeClient.makePayment(anyString(), any())).thenReturn(
                new PaymentResponse(1L, "Payment", "DEBIT", 9876543210L, null,
                        new BigDecimal("9.99"), "COMPLETED", Instant.now(), "Order payment"));

        mockMvc.perform(post("/cart/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER)
                        .header("X-Service-Key", VALID_KEY)
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk());
    }

    // The X-Service-Key proves a trusted caller; the buyer's own token proves who's paying. Having one without
    // the other must not be enough to place a (charged) order.
    @Test
    void addOrderWithValidKeyButNoBuyerTokenIsRejected() throws Exception {
        mockMvc.perform(post("/cart/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }

    // /cart/checkout is the one write endpoint a genuine customer-facing storefront can call directly (see
    // OrderController.checkout) - no X-Service-Key at all, unlike /cart/add above. A CASH order needs no buyer
    // token either.
    private static final String CASH_CHECKOUT = """
            {"customerName":"Buyer","customerPhno":9876543210,"orderItems":[{"productId":1,"productQuantity":1}],"paymentMethod":"CASH"}
            """;

    @Test
    void checkoutWithCashPaymentMethodNeedsOnlyOwnSession() throws Exception {
        Product widget = new Product();
        widget.setProductId(1);
        widget.setProductPrice(9.99);
        widget.setProductStock(10);
        when(productClient.getProductById(1)).thenReturn(widget);

        mockMvc.perform(post("/cart/checkout").contentType(MediaType.APPLICATION_JSON).content(CASH_CHECKOUT).with(customer()))
                .andExpect(status().isOk());
        verifyNoInteractions(phonepeClient);
    }

    // /cart/*/cancel is now the storefront's own self-service cancel - no X-Service-Key required, same as
// /cart/checkout above. The only gate is that a PHONEPE refund needs the real buyer token (see
// OrderController.cancelOrder); an unknown order id 404s regardless of whether one was supplied, same as
// cancelOrderOfAnUnknownOrderIdReturns404 below. See OrderServiceTest for the case that does exercise a
// missing token against a real PHONEPE order.
    @Test
    void cancelOrderWithOwnSessionOfAnUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/cancel").with(customer())).andExpect(status().isNotFound());
    }
    @Test
    void cancelOrderWithValidKeyAndNoBuyerTokenOfAnUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/cancel").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelOrderOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/cancel").header("X-Service-Key", VALID_KEY)
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shipOrderWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/42/ship")).andExpect(status().isUnauthorized());
    }

    @Test
    void shipOrderOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/ship").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deliverOrderWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/42/deliver")).andExpect(status().isUnauthorized());
    }

    @Test
    void deliverOrderOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/deliver").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void markPaidWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/42/markpaid")).andExpect(status().isUnauthorized());
    }

    @Test
    void markPaidOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/markpaid").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    private static final String NEW_COUPON = """
            {"code":"save10","discountPercent":10,"active":true}
            """;

    @Test
    void addCouponWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/coupons/add").contentType(MediaType.APPLICATION_JSON).content(NEW_COUPON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addCouponWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/coupons/add").contentType(MediaType.APPLICATION_JSON).content(NEW_COUPON)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void listingCouponsWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/coupons/all")).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteProductWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/cart/deleteproduct").param("phno", "9876543210").param("productId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listingEveryCustomersOrdersWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/all")).andExpect(status().isUnauthorized());
    }

    @Test
    void listingEveryCustomersOrdersWithValidKeySucceeds() throws Exception {
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void trackingOfAnUnknownOrderIsPublicButReturns404() throws Exception {
        mockMvc.perform(get("/cart/42/tracking")).andExpect(status().isNotFound());
    }

    // Polled by the storefront while a UPI-collect order sits PENDING_PAYMENT - same public trust level as
    // tracking above (just the status of your own order, no X-Service-Key needed).
    @Test
    void paymentStatusOfAnUnknownOrderWithOwnSessionReturns404() throws Exception {
        mockMvc.perform(get("/cart/42/paymentstatus").with(customer())).andExpect(status().isNotFound());
    }

    private static final String NEW_ADDRESS = """
            {"customerPhno":9876543210,"line1":"221B Baker Street","city":"London","state":"Greater London","pincode":"110001"}
            """;

    @Test
    void lookingUpOwnAddressesWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/addresses/byphno").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void addAddressWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/addresses/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ADDRESS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addAddressWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/addresses/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ADDRESS)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void removeAddressWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/addresses/remove").param("phno", "9876543210").param("addressId", "1"))
                .andExpect(status().isUnauthorized());
    }

    // The storefront's own address add/remove - public, no X-Service-Key, same self-service trust level as
    // GET /addresses/byphno (see SecurityConfig).
    @Test
    void addOwnAddressWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(post("/addresses/self/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ADDRESS).with(customer()))
                .andExpect(status().isOk());
    }

    // Public and reachable with no key at all - a non-existent address id still 404s the same way
    // /addresses/remove (X-Service-Key gated) already does, it just doesn't need the key to get there.
    @Test
    void removeOwnAddressWithOwnSessionOfAnUnknownAddressReturns404() throws Exception {
        mockMvc.perform(delete("/addresses/self/remove").param("phno", "9876543210").param("addressId", "999999").with(customer()))
                .andExpect(status().isNotFound());
    }

    // /cart/*/return is now the storefront's own self-service return request - no X-Service-Key required, same
// as /cart/*/cancel above. An unknown order id 404s regardless of whether a key was supplied.
    @Test
    void returnOrderWithOwnSessionOfAnUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/return").param("reason", "damaged").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void returnOrderOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/return").param("reason", "damaged")
                        .header("X-Service-Key", VALID_KEY)
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void notificationsOfAnUnknownOrderIsPublicButReturns404() throws Exception {
        mockMvc.perform(get("/cart/42/notifications")).andExpect(status().isNotFound());
    }

    @Test
    void lookingUpOwnLoyaltyBalanceWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/loyalty/byphno").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnLoyaltyHistoryWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/loyalty/history").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    private static final String LOYALTY_ADJUSTMENT = """
            {"customerPhno":9876543210,"points":25,"reason":"goodwill credit"}
            """;

    @Test
    void adjustLoyaltyPointsWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/loyalty/adjust").contentType(MediaType.APPLICATION_JSON).content(LOYALTY_ADJUSTMENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adjustLoyaltyPointsWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/loyalty/adjust").contentType(MediaType.APPLICATION_JSON).content(LOYALTY_ADJUSTMENT)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void lowStockReportWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/lowstock")).andExpect(status().isUnauthorized());
    }

    @Test
    void lowStockReportWithValidKeySucceeds() throws Exception {
        mockMvc.perform(get("/cart/lowstock").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Review moderation: reporting is for any signed-in customer, the queue and hide/unhide are admin-only.
    @Test
    void reportingAReviewWithoutAnyCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/reviews/flag").param("productId", "1").param("reviewId", "2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInCustomerCanReportAReview() throws Exception {
        mockMvc.perform(post("/cart/reviews/flag").param("productId", "1").param("reviewId", "2").with(customer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void theFlaggedReviewQueueWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/reviews/flagged")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerSessionCannotReadTheFlaggedQueueOrHideReviews() throws Exception {
        mockMvc.perform(get("/cart/reviews/flagged").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(put("/cart/reviews/2/hide").param("productId", "1").with(customer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theFlaggedReviewQueueWithValidKeySucceeds() throws Exception {
        when(productClient.getFlaggedReviews(any(), anyInt(), anyInt()))
                .thenReturn(new com.example.orderservice.dto.ModerationReviewsResult(List.of()));
        mockMvc.perform(get("/cart/reviews/flagged").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Restock / price-drop email job: admin-only.
    @Test
    void runningTheStockAlertJobWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/alerts/run")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerSessionCannotRunTheStockAlertJob() throws Exception {
        mockMvc.perform(post("/cart/alerts/run").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void runningTheStockAlertJobWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/cart/alerts/run").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Pending-payment sweep: admin-only.
    @Test
    void sweepingPendingPaymentsWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/pending/sweep")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerSessionCannotSweepPendingPayments() throws Exception {
        mockMvc.perform(post("/cart/pending/sweep").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void sweepingPendingPaymentsWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/cart/pending/sweep").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Daily admin digest: preview and send are admin-only.
    @Test
    void theDigestEndpointsWithoutKeyAreUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/digest/preview")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/cart/digest/send")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerSessionCannotReadOrSendTheDigest() throws Exception {
        mockMvc.perform(get("/cart/digest/preview").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/digest/send").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void theDigestPreviewWithValidKeySucceedsAndSendingWithoutARecipientIsReportedNotAnError() throws Exception {
        mockMvc.perform(get("/cart/digest/preview").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/cart/digest/send").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Loyalty expiry warning emails: admin-only to trigger by hand.
    @Test
    void runningTheLoyaltyExpiryWarningsWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/loyalty/expiry-warnings/run")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerSessionCannotRunTheLoyaltyExpiryWarnings() throws Exception {
        mockMvc.perform(post("/loyalty/expiry-warnings/run").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void runningTheLoyaltyExpiryWarningsWithValidKeySucceeds() throws Exception {
        mockMvc.perform(post("/loyalty/expiry-warnings/run").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Refer-a-friend: the signed-in customer's own number (or the service key).
    @Test
    void referralEndpointsWithoutCredentialsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/referral/mine").param("phno", "9876543210")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/referral/apply").param("phno", "9876543210").param("code", "ABCD2345"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerCannotUseSomeoneElsesReferralCard() throws Exception {
        mockMvc.perform(get("/referral/mine").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/referral/apply").param("phno", "9000000009").param("code", "ABCD2345").with(customer()))
                .andExpect(status().isForbidden());
    }

    // Product Q&A: reading answers is public, asking is the customer's own, answering is admin-only.
    @Test
    void answeredQuestionsArePublic() throws Exception {
        mockMvc.perform(get("/questions/product").param("productId", "1")).andExpect(status().isOk());
    }

    @Test
    void askingAndListingOwnQuestionsNeedOwnSession() throws Exception {
        mockMvc.perform(post("/questions/ask").param("phno", "9876543210").param("productId", "1").param("question", "Is it waterproof?"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/questions/ask").param("phno", "9000000009").param("productId", "1").param("question", "Is it waterproof?").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/questions/mine").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/questions/mine").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void answeringAndModeratingQuestionsNeedsTheServiceKey() throws Exception {
        mockMvc.perform(get("/questions/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/questions/pending").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(put("/questions/1/answer").param("answer", "Yes").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/questions/1").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/questions/pending").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Pincode serviceability: checking is public (pre-login), managing the list is admin-only.
    @Test
    void pincodeCheckAndSlotsArePublic() throws Exception {
        mockMvc.perform(get("/pincodes/slots")).andExpect(status().isOk());
        mockMvc.perform(get("/pincodes/check").param("pincode", "12")).andExpect(status().isBadRequest());
    }

    @Test
    void managingPincodesNeedsTheServiceKey() throws Exception {
        mockMvc.perform(get("/pincodes/all")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/pincodes/all").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/pincodes/all").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void adminCustomerLookupNeedsTheServiceKey() throws Exception {
        mockMvc.perform(get("/customer/admin/lookup").param("phno", "9876543210")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/customer/admin/lookup").param("phno", "9876543210").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/customer/admin/lookup").param("phno", "9876543210").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/customer/admin/lookup").header("X-Service-Key", VALID_KEY)).andExpect(status().isBadRequest());
    }

    @Test
    void orderHistoryNeedsOwnSessionOrServiceKey() throws Exception {
        mockMvc.perform(get("/cart/history").param("phno", "9876543210")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/history").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/history").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void bulkShipAndDeliverNeedTheServiceKey() throws Exception {
        mockMvc.perform(post("/cart/bulk/ship").contentType("application/json").content("[1]")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/cart/bulk/deliver").contentType("application/json").content("[1]").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/bulk/ship").contentType("application/json").content("[]").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/cart/bulk/ship").contentType("application/json").content("[999999]").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void auditLogIsServiceOnlyAndRecordsServiceKeyWrites() throws Exception {
        mockMvc.perform(get("/audit/recent")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/audit/recent").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/audit/recent").param("limit", "0").header("X-Service-Key", VALID_KEY)).andExpect(status().isBadRequest());

        mockMvc.perform(post("/cart/bulk/ship").contentType("application/json").content("[424242]").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/audit/recent").param("pathContains", "bulk/ship").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].path").value("/cart/bulk/ship"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].status").value(200));
    }

    // Default limit is 15 verify attempts per phone per 15 minutes (LoginRateLimiter); the 16th is refused with 429
    // before the code is even checked. Uses its own phone number so no other test shares the counter.
    @Test
    void repeatedWrongSignInCodesEventuallyGetTooManyRequests() throws Exception {
        String body = "{\"phno\":9111111111,\"code\":\"000000\"}";
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/customer/login/verify").contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/customer/login/verify").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void savedCartIsOwnSessionOnly() throws Exception {
        mockMvc.perform(get("/savedcart").param("phno", "9876543210")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/savedcart").param("phno", "9876543210").contentType("application/json").content("[]"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/savedcart").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(put("/savedcart").param("phno", "9000000009").contentType("application/json").content("[]").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/savedcart").param("phno", "9876543210").contentType("application/json")
                .content("[{\"productId\":1,\"quantity\":2}]").with(customer())).andExpect(status().isOk());
        mockMvc.perform(get("/savedcart").param("phno", "9876543210").with(customer())).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.lines[0].quantity").value(2));
        mockMvc.perform(put("/savedcart").param("phno", "9876543210").contentType("application/json").content("[]").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void orderFeedbackRulesFollowTheCustomerAndServiceSplit() throws Exception {
        mockMvc.perform(post("/feedback/submit").param("phno", "9876543210").param("orderId", "1").param("rating", "5"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/feedback/submit").param("phno", "9000000009").param("orderId", "1").param("rating", "5").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/feedback/submit").param("phno", "9876543210").param("orderId", "424242").param("rating", "5").with(customer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/feedback/mine").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/feedback/mine").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
        mockMvc.perform(get("/feedback/summary").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/feedback/summary").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    // Staff-internal notes: never reachable with a customer session.
    @Test
    void orderNotesAreServiceKeyOnly() throws Exception {
        mockMvc.perform(get("/ordernotes").param("orderId", "1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ordernotes").param("orderId", "1").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/ordernotes").param("orderId", "1").param("note", "hi").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/ordernotes/1").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/ordernotes").param("orderId", "1").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/ordernotes").param("orderId", "424242").param("note", "hi").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void dataExportIsOwnSessionOnlyAndADownload() throws Exception {
        mockMvc.perform(get("/customer/export").param("phno", "9876543210")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/customer/export").param("phno", "9000000009").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/customer/export").param("phno", "9876543210").with(customer()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("my-data-9876543210.json")));
    }

    @Test
    void customerInsightsAreServiceKeyOnly() throws Exception {
        mockMvc.perform(get("/cart/analytics/customers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/analytics/customers").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/analytics/customers").param("top", "0").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cart/analytics/customers").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void cancellationReportIsServiceKeyOnly() throws Exception {
        mockMvc.perform(get("/cart/analytics/cancellations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/analytics/cancellations").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/analytics/cancellations").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void reschedulingDeliveryNeedsTheOrdersOwnerOrTheServiceKey() throws Exception {
        mockMvc.perform(put("/cart/42/delivery").param("deliverySlot", "EVENING")).andExpect(status().isUnauthorized());
        // unknown order (or someone else's) looks the same: 404
        mockMvc.perform(put("/cart/424242/delivery").param("deliverySlot", "EVENING").with(customer())).andExpect(status().isNotFound());
        mockMvc.perform(put("/cart/424242/delivery").param("deliverySlot", "EVENING").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void runningAbandonedCartRemindersNeedsTheServiceKey() throws Exception {
        mockMvc.perform(post("/savedcart/reminders/run")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/savedcart/reminders/run").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/savedcart/reminders/run").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void invoiceOfAnUnknownOrderWithOwnSessionReturns404() throws Exception {
        mockMvc.perform(get("/cart/42/invoice").param("phno", "9876543210").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void invoiceWithoutPhoneNumberIsABadRequest() throws Exception {
        mockMvc.perform(get("/cart/42/invoice").with(customer())).andExpect(status().isBadRequest());
    }

    @Test
    void availableCouponsLookupWithOwnSessionSucceeds() throws Exception {
        mockMvc.perform(get("/coupons/available").param("phno", "9876543210").with(customer())).andExpect(status().isOk());
    }

    @Test
    void allCouponsListingStillRequiresTheKey() throws Exception {
        mockMvc.perform(get("/coupons/all")).andExpect(status().isUnauthorized());
    }

    @Test
    void revenueTimeseriesNeedsTheServiceKey() throws Exception {
        mockMvc.perform(get("/cart/analytics/timeseries")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/analytics/timeseries").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/analytics/timeseries").param("bucket", "week").param("zone", "Asia/Kolkata")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("week"));
    }

    @Test
    void adminOrderSearchWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/orders/search")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminOrderSearchWithValidKeySucceeds() throws Exception {
        mockMvc.perform(get("/cart/orders/search").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void adminOrderExportWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/orders/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminOrderExportWithValidKeyReturnsCsv() throws Exception {
        mockMvc.perform(get("/cart/orders/export").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("orders.csv")));
    }

    @Test
    void adminOrderSearchWithABadStatusFilterIsABadRequest() throws Exception {
        mockMvc.perform(get("/cart/orders/search").param("status", "bogus").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
    // ---- Verified customer sessions: a token only ever unlocks its own phone number's data ----

    private long savedOrderOf(long phno) {
        Cart order = new Cart();
        order.setCustomerName("Buyer");
        order.setCustomerPhno(phno);
        order.setOrderItems(new java.util.ArrayList<>());
        order.setPaymentMethod(PaymentMethod.CASH);
        order.setTotalPrice(120);
        return cartRepository.save(order).getOrderId();
    }

    @Test
    void lookingUpOrdersByPhoneWithoutASessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210")).andExpect(status().isUnauthorized());
    }

    @Test
    void lookingUpOrdersByPhoneWithAnInvalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210").header("X-Customer-Token", "made-up"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lookingUpAnotherCustomersOrdersIsForbidden() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210").with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceKeyCanStillLookUpAnyCustomersOrders() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void customerSessionDoesNotUnlockServiceOnlyEndpoints() throws Exception {
        mockMvc.perform(get("/cart/all").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/42/ship").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void checkingOutForAnotherPhoneNumberIsForbidden() throws Exception {
        mockMvc.perform(post("/cart/checkout").contentType(MediaType.APPLICATION_JSON).content(CASH_CHECKOUT)
                        .with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void writingAReviewUnderAnotherPhoneNumberIsForbidden() throws Exception {
        mockMvc.perform(post("/cart/reviews").param("productId", "1").with(sessionOf(OTHER_CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName":"Alice","reviewerPhno":9876543210,"rating":5,"comment":"Great!"}
                                """))
                .andExpect(status().isForbidden());
    }

    // 404, not 403, so another customer's order ids can't be probed for existence.
    @Test
    void cancellingAnotherCustomersOrderLooksLikeItDoesNotExist() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(post("/cart/" + orderId + "/cancel").with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/cart/" + orderId + "/paymentstatus").with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingAnItemNeedsASession() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(post("/cart/" + orderId + "/items/1/cancel").param("quantity", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancellingOrReturningAnItemOfAnotherCustomersOrderLooksLikeItDoesNotExist() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(post("/cart/" + orderId + "/items/1/cancel").param("quantity", "1").with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/cart/" + orderId + "/items/1/return").param("quantity", "1").param("reason", "x")
                        .with(sessionOf(OTHER_CUSTOMER)))
                .andExpect(status().isNotFound());
    }

    // The fixture order has no items, so the service rejects it - what matters is the request got past security.
    @Test
    void cancellingAnItemOfYourOwnOrderReachesTheService() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(post("/cart/" + orderId + "/items/1/cancel").param("quantity", "1").with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownOrderPaymentStatusWithOwnSessionSucceeds() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(get("/cart/" + orderId + "/paymentstatus").with(customer())).andExpect(status().isOk());
    }

    @Test
    void guestOrderSummaryIsPublicWithTheMatchingPhoneNumber() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(get("/cart/" + orderId + "/summary").param("phno", "9876543210"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalPrice").value(120.0))
                .andExpect(jsonPath("$.customerPhno").doesNotExist());
    }

    @Test
    void guestOrderSummaryWithTheWrongPhoneNumberReturns404() throws Exception {
        long orderId = savedOrderOf(CUSTOMER);
        mockMvc.perform(get("/cart/" + orderId + "/summary").param("phno", "9123456789"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sessionEndpointReportsTheSignedInPhoneNumber() throws Exception {
        mockMvc.perform(get("/customer/session").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phno").value(CUSTOMER));
    }

    @Test
    void sessionEndpointNeedsACustomerSession() throws Exception {
        mockMvc.perform(get("/customer/session")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/customer/session").header("X-Service-Key", VALID_KEY)).andExpect(status().isForbidden());
    }

    @Test
    void loggingOutInvalidatesTheToken() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();
        mockMvc.perform(post("/customer/logout").header("X-Customer-Token", token)).andExpect(status().isNoContent());
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210").header("X-Customer-Token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEmailRebindRequiresTheServiceKey() throws Exception {
        mockMvc.perform(put("/customer/admin/email").param("phno", "9876543210").param("email", "a@b.com"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/customer/admin/email").param("phno", "9876543210").param("email", "a@b.com")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isNoContent());
    }
}
