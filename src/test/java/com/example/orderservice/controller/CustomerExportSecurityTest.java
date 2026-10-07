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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The customer CSV holds every customer's phone and email, so it is service-key only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerExportSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties

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
    void withoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/cart/analytics/customers/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInCustomerIsForbidden() throws Exception {
        String token = customerAuthService.issueSession(9876500021L).token();

        mockMvc.perform(get("/cart/analytics/customers/export").header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void theServiceKeyGetsACsvAttachment() throws Exception {
        mockMvc.perform(get("/cart/analytics/customers/export").param("segment", "repeat").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"customers.csv\""))
                .andExpect(content().string(startsWith("customerPhno,customerName,email,")));
    }

    @Test
    void anUnknownSegmentIsABadRequest() throws Exception {
        mockMvc.perform(get("/cart/analytics/customers/export").param("segment", "vip").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
