package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CouponRepository;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Minting coupon codes is service-key only, and the codes it mints never reach the customer-facing suggestions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BulkCouponSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final String BODY = "{\"prefix\":\"SECT\",\"count\":3,\"discountPercent\":15}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerAuthService customerAuthService;
    @Autowired
    private CouponRepository coupons;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @Test
    void withoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(post("/coupons/bulk").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInCustomerIsForbidden() throws Exception {
        String token = customerAuthService.issueSession(9876500031L).token();

        mockMvc.perform(post("/coupons/bulk").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void theServiceKeyMintsCodesThatCustomersCannotSee() throws Exception {
        mockMvc.perform(post("/coupons/bulk").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes.length()").value(3))
                .andExpect(jsonPath("$.codes[0]").value(org.hamcrest.Matchers.startsWith("SECT-")));
        assertEquals(3, coupons.findAll().stream().filter(c -> c.getCode().startsWith("SECT-")).count());

        long phno = 9876500032L;
        String token = customerAuthService.issueSession(phno).token();
        mockMvc.perform(get("/coupons/available").param("phno", String.valueOf(phno)).header("X-Customer-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code =~ /SECT-.*/)]").isEmpty());
    }

    @Test
    void aBadRequestIsA400() throws Exception {
        mockMvc.perform(post("/coupons/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"OK\",\"count\":0,\"discountPercent\":15}")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
