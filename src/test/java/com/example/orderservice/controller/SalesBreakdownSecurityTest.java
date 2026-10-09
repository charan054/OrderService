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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sales figures are for the shop owner: the service key only - not the public, not a signed-in customer. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesBreakdownSecurityTest {
    private static final String VALID_KEY = "test-service-key";

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
    void neitherTheAnonymousNorACustomerMaySeeSales() throws Exception {
        String customer = customerAuthService.issueSession(9876500101L).token();
        mockMvc.perform(get("/cart/analytics/breakdown")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/analytics/breakdown").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/cart/analytics/breakdown/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/analytics/breakdown/export").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
    }

    @Test
    void theServiceKeyGetsTheBreakdownAndTheCsv() throws Exception {
        when(productClient.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/cart/analytics/breakdown").param("groupBy", "category").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("category"))
                .andExpect(jsonPath("$.rows").isArray());
        mockMvc.perform(get("/cart/analytics/breakdown/export").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sales-by-product.csv")));
    }

    @Test
    void badParametersAreA400() throws Exception {
        mockMvc.perform(get("/cart/analytics/breakdown").param("groupBy", "brand").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cart/analytics/breakdown").param("sort", "nope").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
