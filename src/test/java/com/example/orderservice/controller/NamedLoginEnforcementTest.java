package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.service.AdminAuthService;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * admin.require-named-login=true: browsers can no longer use the shared service key (once an owner exists), while
 * scripts, named admins and customers are unaffected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "admin.require-named-login=true")
class NamedLoginEnforcementTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final String PASSWORD = "correct horse battery";
    private static final String ORIGIN = "http://localhost:8083";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private CustomerAuthService customerAuthService;
    @Autowired
    private AdminAccountRepository accounts;
    @Autowired
    private AdminSessionRepository sessions;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @BeforeEach
    void reset() {
        sessions.deleteAll();
        accounts.deleteAll();
    }

    private String tokenFor(String username, AdminRole role) {
        adminAuthService.create(new NewAdminAccount(username, PASSWORD, role), "service-key");
        return adminAuthService.login(username, PASSWORD).token();
    }

    @Test
    void withNoOwnerYetTheSwitchIsIgnoredSoTheDashboardCanStillBootstrap() throws Exception {
        mockMvc.perform(get("/admin/config")).andExpect(jsonPath("$.namedLoginRequired").value(false));
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Origin", ORIGIN))
                .andExpect(status().isOk());
        tokenFor("manager1", AdminRole.MANAGER); // not an owner
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
    }

    @Test
    void onceAnOwnerExistsABrowserCannotUseTheServiceKey() throws Exception {
        tokenFor("owner1", AdminRole.OWNER);

        mockMvc.perform(get("/admin/config")).andExpect(jsonPath("$.namedLoginRequired").value(true));
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/audit/recent").header("X-Service-Key", VALID_KEY).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scriptsKeepUsingTheServiceKeyBecauseTheySendNoBrowserHeaders() throws Exception {
        tokenFor("owner1", AdminRole.OWNER);

        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/accounts").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void namedAdminsAndCustomersAreNotAffectedFromABrowser() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String customer = customerAuthService.issueSession(9876500131L).token();

        mockMvc.perform(get("/cart/all").header("X-Admin-Token", owner).header("Origin", ORIGIN)).andExpect(status().isOk());
        mockMvc.perform(get("/customer/session").header("X-Customer-Token", customer).header("Origin", ORIGIN)).andExpect(status().isOk());
        mockMvc.perform(get("/cart/display").header("Origin", ORIGIN)).andExpect(status().isOk());
    }

    @Test
    void aWrongKeyIsStillJustUnauthorizedAndADisabledOwnerDoesNotCount() throws Exception {
        tokenFor("owner1", AdminRole.OWNER);
        tokenFor("owner2", AdminRole.OWNER);
        adminAuthService.setActive("owner2", false);

        mockMvc.perform(get("/cart/all").header("X-Service-Key", "wrong").header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
        // owner1 is still active, so the requirement stands; remove the account and it lifts again.
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
        accounts.deleteAll();
        mockMvc.perform(get("/cart/all").header("X-Service-Key", VALID_KEY).header("Origin", ORIGIN)).andExpect(status().isOk());
    }
}
