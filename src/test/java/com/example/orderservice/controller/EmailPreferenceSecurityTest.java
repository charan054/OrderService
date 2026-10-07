package com.example.orderservice.controller;

import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.service.CustomerAuthService;
import com.example.orderservice.service.EmailPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotional-email preferences: the unsubscribe link is public but only works with its signed token; the switch
 * in "My account" is the signed-in customer's own (or the service key's). Real SecurityFilterChain and H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailPreferenceSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final long CUSTOMER = 9876500001L;
    private static final long OTHER_CUSTOMER = 9876500002L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerAuthService customerAuthService;
    @Autowired
    private CustomerAccountRepository accounts;
    @Autowired
    private EmailPreferenceService preferences;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @BeforeEach
    void freshAccounts() {
        for (long phno : new long[]{CUSTOMER, OTHER_CUSTOMER}) {
            CustomerAccount a = new CustomerAccount();
            a.setPhno(phno);
            a.setEmail(phno + "@example.com");
            a.setVerifiedAt(Instant.now());
            accounts.save(a);
        }
    }

    private String session(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    @Test
    void theUnsubscribeLinkWorksWithoutSigningInWhenTheTokenIsValid() throws Exception {
        String token = preferences.token(CUSTOMER);

        mockMvc.perform(get("/prefs/unsubscribe").param("phno", String.valueOf(CUSTOMER)).param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unsubscribed")));

        assertTrue(accounts.findById(CUSTOMER).orElseThrow().isMarketingOptOut());
    }

    @Test
    void theUnsubscribeLinkRejectsAWrongTokenAndChangesNothing() throws Exception {
        mockMvc.perform(get("/prefs/unsubscribe").param("phno", String.valueOf(OTHER_CUSTOMER))
                        .param("token", preferences.token(CUSTOMER)))
                .andExpect(status().isForbidden());

        assertFalse(accounts.findById(OTHER_CUSTOMER).orElseThrow().isMarketingOptOut());
    }

    @Test
    void thePreferenceEndpointsNeedASessionOrTheServiceKey() throws Exception {
        mockMvc.perform(get("/prefs/mine").param("phno", String.valueOf(CUSTOMER))).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/prefs/mine").param("phno", String.valueOf(CUSTOMER)).param("marketingEmails", "false"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerCanReadAndChangeTheirOwnPreference() throws Exception {
        String token = session(CUSTOMER);

        mockMvc.perform(get("/prefs/mine").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marketingEmails").value(true));
        mockMvc.perform(put("/prefs/mine").param("phno", String.valueOf(CUSTOMER)).param("marketingEmails", "false")
                        .header("X-Customer-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marketingEmails").value(false));
        mockMvc.perform(get("/prefs/mine").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(jsonPath("$.marketingEmails").value(false));
    }

    @Test
    void aCustomerCannotTouchSomeoneElsesPreference() throws Exception {
        String token = session(CUSTOMER);

        mockMvc.perform(get("/prefs/mine").param("phno", String.valueOf(OTHER_CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/prefs/mine").param("phno", String.valueOf(OTHER_CUSTOMER)).param("marketingEmails", "false")
                        .header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        assertFalse(accounts.findById(OTHER_CUSTOMER).orElseThrow().isMarketingOptOut());
    }

    @Test
    void theServiceKeyMayChangeAnyonesPreference() throws Exception {
        mockMvc.perform(put("/prefs/mine").param("phno", String.valueOf(OTHER_CUSTOMER)).param("marketingEmails", "false")
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());

        assertTrue(accounts.findById(OTHER_CUSTOMER).orElseThrow().isMarketingOptOut());
    }
}
