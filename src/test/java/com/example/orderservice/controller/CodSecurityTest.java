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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer may see their own cash-on-delivery status; only the service key may see anyone's or override it. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final long CUSTOMER = 9876500041L;
    private static final long OTHER = 9876500042L;

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
    void ownStatusNeedsASession() throws Exception {
        mockMvc.perform(get("/customer/cod").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/customer/cod").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
        mockMvc.perform(get("/customer/cod").param("phno", String.valueOf(OTHER)).header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAdminEndpointsAreServiceKeyOnly() throws Exception {
        String token = session(CUSTOMER);
        mockMvc.perform(get("/customer/admin/cod").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/customer/admin/cod").param("phno", String.valueOf(CUSTOMER)).param("mode", "ALLOW")
                        .header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/customer/admin/cod").param("phno", String.valueOf(CUSTOMER)).param("mode", "ALLOW"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theServiceKeyCanBlockAndRestoreCash() throws Exception {
        mockMvc.perform(put("/customer/admin/cod").param("phno", String.valueOf(OTHER)).param("mode", "BLOCK")
                        .param("note", "refused twice").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.override").value("BLOCK"));
        mockMvc.perform(put("/customer/admin/cod").param("phno", String.valueOf(OTHER)).param("mode", "AUTO")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.override").doesNotExist());
    }
}
