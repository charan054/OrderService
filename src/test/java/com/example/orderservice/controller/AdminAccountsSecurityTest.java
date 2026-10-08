package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.entity.AuditLogEntry;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminLoginEventRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.repository.AuditLogRepository;
import com.example.orderservice.service.AdminAuthService;
import com.example.orderservice.service.CustomerAuthService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Named admin accounts through the real security chain: who may sign in, what each role may call, that the shared
 * service key keeps working, and that the audit log says who did what. Tokens are obtained straight from the
 * service (not /admin/login) where the login endpoint is not what is being tested, to stay under its rate limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccountsSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final String PASSWORD = "correct horse battery";

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
    @Autowired
    private AuditLogRepository auditLog;
    @Autowired
    private AdminLoginEventRepository loginEvents;

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
        loginEvents.deleteAll();
        // Names with audit history can't be reused for new accounts, and every test reuses the same few names.
        auditLog.deleteAll();
    }

    private String tokenFor(String username, AdminRole role) {
        adminAuthService.create(new NewAdminAccount(username, PASSWORD, role), "service-key");
        return adminAuthService.login(username, PASSWORD).token();
    }

    private List<AuditLogEntry> auditedFor(String actor) {
        return auditLog.findByActorIgnoreCaseOrderByIdDesc(actor, PageRequest.of(0, 20));
    }

    private static String newAccount(String username, String role) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void theServiceKeyBootstrapsTheFirstOwnerWhoThenSignsIn() throws Exception {
        mockMvc.perform(post("/admin/accounts").header("X-Service-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(newAccount("founder", "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("founder"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.createdBy").value("service-key"))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("$2"))));

        String body = mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"founder\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");

        mockMvc.perform(get("/admin/me").header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("founder"))
                .andExpect(jsonPath("$.serviceKey").value(false));
    }

    @Test
    void badCredentialsAreAll401WithTheSameMessage() throws Exception {
        adminAuthService.create(new NewAdminAccount("asha", PASSWORD, AdminRole.SUPPORT), "service-key");

        for (String json : List.of(
                "{\"username\":\"asha\",\"password\":\"not the password\"}",
                "{\"username\":\"nobody\",\"password\":\"" + PASSWORD + "\"}",
                "{\"username\":\"asha\"}")) {
            mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Invalid username or password."));
        }
    }

    @Test
    void loginIsRateLimitedPerUsernameEvenWithTheRightPasswordAfterwards() throws Exception {
        adminAuthService.create(new NewAdminAccount("hammered", PASSWORD, AdminRole.SUPPORT), "service-key");
        String wrong = "{\"username\":\"hammered\",\"password\":\"wrong password!\"}";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hammered\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void accountManagementIsForOwnersAndTheServiceKeyOnly() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String manager = tokenFor("manager1", AdminRole.MANAGER);
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(get("/admin/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/accounts").header("X-Admin-Token", "not-a-token")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/accounts").header("X-Admin-Token", support))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("needs OWNER")));
        mockMvc.perform(get("/admin/accounts").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/accounts").header("X-Admin-Token", manager)
                        .contentType(MediaType.APPLICATION_JSON).content(newAccount("sneaky", "OWNER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/accounts").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/admin/accounts").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        assertThat(accounts.findByUsername("sneaky")).isEmpty();
    }

    @Test
    void aCustomerSessionNeverSatisfiesAdminEndpoints() throws Exception {
        String customer = customerAuthService.issueSession(9876500071L).token();

        mockMvc.perform(get("/admin/accounts").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/audit/recent").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
    }

    @Test
    void eachRoleReachesExactlyItsOwnLevel() throws Exception {
        String support = tokenFor("support1", AdminRole.SUPPORT);
        String manager = tokenFor("manager1", AdminRole.MANAGER);
        String owner = tokenFor("owner1", AdminRole.OWNER);

        // Look-ups: everyone.
        for (String token : List.of(support, manager, owner)) {
            mockMvc.perform(get("/cart/all").header("X-Admin-Token", token)).andExpect(status().isOk());
            mockMvc.perform(get("/cart/byphno").param("phno", "9876500071").header("X-Admin-Token", token)).andExpect(status().isOk());
        }
        // Operations and money: manager and up.
        mockMvc.perform(get("/coupons/all").header("X-Admin-Token", support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/1/ship").header("X-Admin-Token", support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/1/cancel").header("X-Admin-Token", support)).andExpect(status().isForbidden());
        mockMvc.perform(get("/coupons/all").header("X-Admin-Token", manager)).andExpect(status().isOk());
        mockMvc.perform(get("/coupons/all").header("X-Admin-Token", owner)).andExpect(status().isOk());
        // Owner-only: the audit trail and handing out credit/points.
        mockMvc.perform(get("/audit/recent").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(post("/storecredit/adjust").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(post("/loyalty/adjust").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/audit/recent").header("X-Admin-Token", owner)).andExpect(status().isOk());
    }

    @Test
    void theServiceKeyIsNotHeldToAnyRole() throws Exception {
        mockMvc.perform(get("/audit/recent").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/me").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("service-key"))
                .andExpect(jsonPath("$.serviceKey").value(true));
    }

    @Test
    void aValidServiceKeyWinsOverAnAdminTokenSentAlongsideIt() throws Exception {
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(get("/audit/recent").header("X-Service-Key", VALID_KEY).header("X-Admin-Token", support))
                .andExpect(status().isOk());
    }

    @Test
    void demotingAnAdminChangesWhatTheirExistingTokenCanDo() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String manager = tokenFor("manager1", AdminRole.MANAGER);
        mockMvc.perform(get("/coupons/all").header("X-Admin-Token", manager)).andExpect(status().isOk());

        mockMvc.perform(put("/admin/accounts/manager1/role").param("role", "SUPPORT").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPPORT"));

        mockMvc.perform(get("/coupons/all").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
    }

    @Test
    void disablingAnAdminCutsOffTheirTokenAtOnce() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String support = tokenFor("support1", AdminRole.SUPPORT);
        mockMvc.perform(get("/cart/all").header("X-Admin-Token", support)).andExpect(status().isOk());

        mockMvc.perform(put("/admin/accounts/support1/active").param("active", "false").header("X-Admin-Token", owner))
                .andExpect(status().isOk());

        mockMvc.perform(get("/cart/all").header("X-Admin-Token", support)).andExpect(status().isUnauthorized());
    }

    @Test
    void theLastOwnerCannotBeRemovedEvenByThemselves() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);

        mockMvc.perform(put("/admin/accounts/owner1/role").param("role", "MANAGER").header("X-Admin-Token", owner))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("at least one active owner")));
        mockMvc.perform(put("/admin/accounts/owner1/active").param("active", "false").header("X-Admin-Token", owner))
                .andExpect(status().isConflict());
    }

    @Test
    void anOwnerResetsAForgottenPasswordAndTheOldOneStopsWorking() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(put("/admin/accounts/support1/password").header("X-Admin-Token", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"a brand new passphrase\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/cart/all").header("X-Admin-Token", support)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"support1\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"support1\",\"password\":\"a brand new passphrase\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anyAdminChangesTheirOwnPasswordButTheServiceKeyHasNoneToChange() throws Exception {
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(put("/admin/me/password").header("X-Admin-Token", support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong wrong wrong\",\"newPassword\":\"a brand new passphrase\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/me/password").header("X-Admin-Token", support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a brand new passphrase\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/admin/me/password").header("X-Service-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"a brand new passphrase\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutEndsTheSessionAndIsAlwaysSafeToCall() throws Exception {
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(post("/admin/logout").header("X-Admin-Token", support)).andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/logout").header("X-Admin-Token", support)).andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/logout")).andExpect(status().isNoContent());

        mockMvc.perform(get("/cart/all").header("X-Admin-Token", support)).andExpect(status().isUnauthorized());
    }

    @Test
    void theAuditLogNamesWhoDidWhatIncludingRefusedAttempts() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String support = tokenFor("support1", AdminRole.SUPPORT);

        mockMvc.perform(post("/admin/accounts").header("X-Admin-Token", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(newAccount("newbie", "SUPPORT")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/cart/1/ship").header("X-Admin-Token", support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/accounts").header("X-Service-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(newAccount("viakey", "SUPPORT")))
                .andExpect(status().isOk());

        assertThat(auditedFor("owner1")).anySatisfy(e -> {
            assertThat(e.getMethod()).isEqualTo("POST");
            assertThat(e.getPath()).isEqualTo("/admin/accounts");
            assertThat(e.getStatus()).isEqualTo(200);
        });
        assertThat(auditedFor("support1")).anySatisfy(e -> {
            assertThat(e.getPath()).isEqualTo("/cart/1/ship");
            assertThat(e.getStatus()).isEqualTo(403);
        });
        assertThat(auditedFor("service-key")).anySatisfy(e -> assertThat(e.getPath()).isEqualTo("/admin/accounts"));
        // Passwords travel in the body, so they can never end up in a recorded path.
        assertThat(auditLog.findAll()).noneSatisfy(e -> assertThat(e.getPath()).contains(PASSWORD));
    }

    @Test
    void theConfigIsPublicAndStaysOffByDefault() throws Exception {
        mockMvc.perform(get("/admin/config")).andExpect(status().isOk()).andExpect(jsonPath("$.namedLoginRequired").value(false));
        String support = tokenFor("support1", AdminRole.SUPPORT);
        mockMvc.perform(get("/admin/config").header("X-Admin-Token", support)).andExpect(status().isOk());
    }

    @Test
    void signInHistoryAndDeletionAreOwnerOnly() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String manager = tokenFor("manager1", AdminRole.MANAGER);
        adminAuthService.create(new NewAdminAccount("mistake", PASSWORD, AdminRole.SUPPORT), "owner1");

        mockMvc.perform(get("/admin/logins").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/accounts/mistake").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logins").param("username", "owner1").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("owner1"))
                .andExpect(jsonPath("$[0].result").value("OK"))
                .andExpect(content().string(not(containsString(PASSWORD))));
        mockMvc.perform(get("/admin/logins").param("limit", "0").header("X-Admin-Token", owner)).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/admin/accounts/owner1").header("X-Admin-Token", owner)).andExpect(status().isConflict());
        mockMvc.perform(delete("/admin/accounts/mistake").header("X-Admin-Token", owner)).andExpect(status().isNoContent());
        assertThat(accounts.findByUsername("mistake")).isEmpty();
        mockMvc.perform(get("/admin/accounts").header("X-Admin-Token", owner)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theLoginEndpointRecordsTheCallersAddress() throws Exception {
        adminAuthService.create(new NewAdminAccount("recorded", PASSWORD, AdminRole.SUPPORT), "service-key");

        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"recorded\",\"password\":\"wrong password!\"}")).andExpect(status().isUnauthorized());

        assertThat(loginEvents.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getResult()).isEqualTo("WRONG_PASSWORD");
            assertThat(e.getRemoteAddr()).isNotBlank();
        });
    }

    @Test
    void theAuditEndpointCanFilterByActor() throws Exception {
        String owner = tokenFor("owner1", AdminRole.OWNER);
        String support = tokenFor("support1", AdminRole.SUPPORT);
        mockMvc.perform(post("/cart/1/ship").header("X-Admin-Token", support)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/accounts").header("X-Admin-Token", owner)
                .contentType(MediaType.APPLICATION_JSON).content(newAccount("newbie", "SUPPORT")));

        mockMvc.perform(get("/audit/recent").param("actor", "SUPPORT1").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("support1"))
                .andExpect(content().string(not(containsString("owner1"))));
        mockMvc.perform(get("/audit/recent").param("actor", "owner1").param("pathContains", "/admin/").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("/admin/accounts"));
    }
}
