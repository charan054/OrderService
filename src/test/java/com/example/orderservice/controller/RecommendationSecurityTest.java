package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recommendations come from one customer's purchases, so they are that customer's (or the service key's) only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationSecurityTest {
    private static final String VALID_KEY = "test-service-key";
    private static final long CUSTOMER = 9876500081L;
    private static final long OTHER = 9876500082L;

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

    private String session(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    @Test
    void needsASessionAndOnlyServesYourOwnNumber() throws Exception {
        mockMvc.perform(get("/cart/recommendations").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/recommendations").param("phno", String.valueOf(CUSTOMER))
                        .header("X-Customer-Token", session(OTHER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/recommendations").param("phno", String.valueOf(CUSTOMER))
                        .header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void theServiceKeyMayReadAnyCustomersRecommendations() throws Exception {
        mockMvc.perform(get("/cart/recommendations").param("phno", String.valueOf(CUSTOMER)).header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }
}
