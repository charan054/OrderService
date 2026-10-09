package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.ServiceHealth;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import com.example.orderservice.service.HealthService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Health history is for the shop owner: service key only. A recorded sample then shows up in the history. Real H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthHistorySecurityTest {
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
    @MockitoBean
    private HealthService healthService;

    @Test
    void neitherTheAnonymousNorACustomerMaySeeOrRecordHealth() throws Exception {
        String customer = customerAuthService.issueSession(9876500131L).token();
        mockMvc.perform(get("/cart/health/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/cart/health/history/record")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/health/history").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/health/history/record").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
    }

    @Test
    void aRecordedSampleAppearsInTheHistory() throws Exception {
        when(healthService.check()).thenReturn(List.of(
                new ServiceHealth("OrderService", "UP", 0, "answering this request"),
                new ServiceHealth("ProductService", "DOWN", 2000, "not reachable")));

        mockMvc.perform(post("/cart/health/history/record").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/cart/health/history").param("hours", "6").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(6))
                .andExpect(jsonPath("$.services[?(@.name=='OrderService')].uptimePercent").value(100.0))
                .andExpect(jsonPath("$.services[?(@.name=='ProductService')].uptimePercent").value(0.0))
                .andExpect(jsonPath("$.services[?(@.name=='OrderService')].buckets.length()").value(6));
    }

    @Test
    void aBadWindowIsA400() throws Exception {
        mockMvc.perform(get("/cart/health/history").param("hours", "0").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cart/health/history").param("hours", "500").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
