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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Email me this invoice" needs a session for the same phone number (or the service key). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceEmailSecurityTest {
    private static final long CUSTOMER = 9876500011L;
    private static final long OTHER_CUSTOMER = 9876500012L;

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
        mockMvc.perform(post("/cart/42/invoice/email").param("phno", String.valueOf(CUSTOMER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCustomersPhoneNumberIsForbidden() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();

        mockMvc.perform(post("/cart/42/invoice/email").param("phno", String.valueOf(OTHER_CUSTOMER))
                        .header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownSessionReachesTheServiceAndAnUnknownOrderIsNotFound() throws Exception {
        String token = customerAuthService.issueSession(CUSTOMER).token();

        mockMvc.perform(post("/cart/424242/invoice/email").param("phno", String.valueOf(CUSTOMER))
                        .header("X-Customer-Token", token))
                .andExpect(status().isNotFound());
    }
}
