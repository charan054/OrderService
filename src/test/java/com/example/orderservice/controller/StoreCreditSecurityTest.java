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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer sees only their own store credit; only the service key can add or take it away. Real H2 database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoreCreditSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final long CUSTOMER = 9876500051L;
    private static final long OTHER = 9876500052L;

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
    void ownBalanceNeedsASessionAndOnlyShowsYourOwn() throws Exception {
        mockMvc.perform(get("/storecredit/byphno").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/storecredit/byphno").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.0));
        mockMvc.perform(get("/storecredit/byphno").param("phno", String.valueOf(OTHER)).header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotGiveThemselvesCredit() throws Exception {
        mockMvc.perform(post("/storecredit/adjust").param("phno", String.valueOf(CUSTOMER)).param("amount", "1000")
                        .param("note", "free money").header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/storecredit/adjust").param("phno", String.valueOf(CUSTOMER)).param("amount", "1000")
                        .param("note", "free money"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theServiceKeyAdjustsAndTheHistoryShowsIt() throws Exception {
        mockMvc.perform(post("/storecredit/adjust").param("phno", String.valueOf(OTHER)).param("amount", "250")
                        .param("note", "late delivery goodwill").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.0))
                .andExpect(jsonPath("$.transactions[0].type").value("ADJUSTED"))
                .andExpect(jsonPath("$.transactions[0].note").value("late delivery goodwill"));
        mockMvc.perform(post("/storecredit/adjust").param("phno", String.valueOf(OTHER)).param("amount", "-300")
                        .param("note", "too much").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/storecredit/byphno").param("phno", String.valueOf(OTHER)).header("X-Customer-Token", session(OTHER)))
                .andExpect(jsonPath("$.balance").value(250.0));
    }
}
