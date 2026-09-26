package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.Product;
import com.example.orderservice.kafka.OrderKafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsing the catalog and looking up one's own orders by phone are public; placing/removing an order and
 * listing EVERY customer's orders need the right X-Service-Key. Real SecurityFilterChain, real (in-memory)
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

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private PhonepeClient phonepeClient;

    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private static final String NEW_ORDER = """
            {"customerName":"Buyer","customerPhno":9876543210,"orderItems":[{"productId":1,"productQuantity":1}]}
            """;

    @Test
    void catalogBrowsingIsPublic() throws Exception {
        when(productClient.findAll()).thenReturn(List.of());
        mockMvc.perform(get("/cart/display")).andExpect(status().isOk());
    }

    // Regression: the static dashboard was 401ing before Spring Security's static-resource handler ever got to
    // serve it, because nothing explicitly permitted it.
    @Test
    void staticDashboardIsPublic() throws Exception {
        mockMvc.perform(get("/cart.html")).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnOrdersByPhoneIsPublic() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210")).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnWishlistByPhoneIsPublic() throws Exception {
        mockMvc.perform(get("/wishlist/byphno").param("phno", "9876543210")).andExpect(status().isOk());
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

    @Test
    void cancelOrderWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/42/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void cancelOrderWithValidKeyButNoBuyerTokenIsRejected() throws Exception {
        mockMvc.perform(post("/cart/42/cancel").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
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

    private static final String NEW_ADDRESS = """
            {"customerPhno":9876543210,"line1":"221B Baker Street","city":"London","state":"Greater London","pincode":"110001"}
            """;

    @Test
    void lookingUpOwnAddressesByPhoneIsPublic() throws Exception {
        mockMvc.perform(get("/addresses/byphno").param("phno", "9876543210")).andExpect(status().isOk());
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

    @Test
    void returnOrderWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/cart/42/return").param("reason", "damaged")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnOrderWithValidKeyButNoBuyerTokenIsRejected() throws Exception {
        mockMvc.perform(post("/cart/42/return").param("reason", "damaged").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnOrderOfAnUnknownOrderIdReturns404() throws Exception {
        mockMvc.perform(post("/cart/42/return").param("reason", "damaged")
                        .header("X-Service-Key", VALID_KEY)
                        .header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isNotFound());
    }
}
