package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Subscriptions: the offer is public, a customer manages only their own, running the due orders is service-only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubscriptionSecurityTest {
    private static final long CUSTOMER = 9876500031L;
    private static final long OTHER = 9876500032L;
    private static final String KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerAuthService customerAuthService;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @Test
    void theOfferIsPublic() throws Exception {
        mockMvc.perform(get("/subscriptions/terms")).andExpect(status().isOk()).andExpect(jsonPath("$.discountPercent").value(5.0));
    }

    @Test
    void managingSubscriptionsNeedsASession() throws Exception {
        mockMvc.perform(get("/subscriptions/mine").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phno\":" + CUSTOMER + ",\"productId\":1,\"quantity\":1,\"intervalDays\":30}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/subscriptions/1/pause").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/subscriptions/1").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerCannotActOnAnotherPhoneNumber() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();

        mockMvc.perform(get("/subscriptions/mine").param("phno", String.valueOf(OTHER)).header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).header("X-Customer-Token", token)
                .content("{\"phno\":" + OTHER + ",\"productId\":1,\"quantity\":1,\"intervalDays\":30}")).andExpect(status().isForbidden());
    }

    @Test
    void ownSessionListsItsOwnAndAnUnknownSubscriptionIsNotFound() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();

        mockMvc.perform(get("/subscriptions/mine").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(put("/subscriptions/424242/skip").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void runningDueOrdersAndListingEverythingIsServiceOnly() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();

        mockMvc.perform(post("/subscriptions/run")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/subscriptions/run").header("X-Customer-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/subscriptions/all").header("X-Customer-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(post("/subscriptions/run").header("X-Service-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.placed").value(0));
        mockMvc.perform(get("/subscriptions/all").header("X-Service-Key", KEY)).andExpect(status().isOk());
    }
}
