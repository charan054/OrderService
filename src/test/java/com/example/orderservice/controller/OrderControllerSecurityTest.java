package com.example.orderservice.controller;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsing the catalog and looking up one's own orders by phone are public; placing/removing an order and
 * listing EVERY customer's orders need the right X-Service-Key. Real SecurityFilterChain, real (in-memory)
 * database - only ProductClient (the Feign call to the real ProductService) is stubbed out.
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

    private static final String NEW_ORDER = """
            {"customerName":"Buyer","customerPhno":9876543210,"orderItems":[{"productId":1,"productQuantity":1}]}
            """;

    @Test
    void catalogBrowsingIsPublic() throws Exception {
        when(productClient.findAll()).thenReturn(List.of());
        mockMvc.perform(get("/cart/display")).andExpect(status().isOk());
    }

    @Test
    void lookingUpOwnOrdersByPhoneIsPublic() throws Exception {
        mockMvc.perform(get("/cart/byphno").param("phno", "9876543210")).andExpect(status().isOk());
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

        mockMvc.perform(post("/cart/add").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
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
}
