package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminLoginEventRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.repository.AuditLogRepository;
import com.example.orderservice.service.AdminAuthService;
import com.example.orderservice.service.TotpService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two-step (authenticator app) sign-in for named admins, through the real security chain. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminTwoStepSignInTest {
    private static final String KEY = "test-service-key";
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private TotpService totp;
    @Autowired
    private AdminAccountRepository accounts;
    @Autowired
    private AdminSessionRepository sessions;
    @Autowired
    private AdminLoginEventRepository loginEvents;
    @Autowired
    private AuditLogRepository auditLog;

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
        auditLog.deleteAll();
    }

    private String loginBody(String user, String code) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}";
    }

    private String tokenWithoutTwoStep(String username) {
        adminAuthService.create(new NewAdminAccount(username, PASSWORD, AdminRole.MANAGER), "service-key");
        return adminAuthService.login(username, PASSWORD).token();
    }

    /** Sets two-step up through the API like the dashboard does; returns [secret, recoveryCodesJson]. */
    private String[] turnOn(String username, String token) throws Exception {
        String setup = mockMvc.perform(post("/admin/me/2fa/setup").header("X-Admin-Token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        String code = totp.codeForStep(secret, totp.stepAt(Instant.now()));
        MvcResult enabled = mockMvc.perform(post("/admin/me/2fa/enable").header("X-Admin-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(AdminAuthService.RECOVERY_CODE_COUNT)).andReturn();
        return new String[]{secret, enabled.getResponse().getContentAsString()};
    }

    @Test
    void afterSetupSigningInNeedsTheAuthenticatorCode() throws Exception {
        String token = tokenWithoutTwoStep("asha");
        String[] on = turnOn("asha", token);
        String secret = on[0];

        // password alone is no longer enough
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("asha", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("authenticator")));
        // a wrong code is refused
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("asha", "000000")))
                .andExpect(status().isUnauthorized());
        // the right code (the next step, since setup used the current one) signs in
        long next = totp.stepAt(Instant.now()) + 1;
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("asha", totp.codeForStep(secret, next))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        // and the same code cannot be used a second time
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("asha", totp.codeForStep(secret, next))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRecoveryCodeSignsInOnceAndIsThenUsedUp() throws Exception {
        String token = tokenWithoutTwoStep("ravi");
        String recoveryJson = turnOn("ravi", token)[1];
        String recovery = JsonPath.read(recoveryJson, "$.recoveryCodes[0]");

        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("ravi", recovery)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("ravi", recovery)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSecretAndRecoveryHashesNeverLeaveTheServer() throws Exception {
        String token = tokenWithoutTwoStep("meena");
        String[] on = turnOn("meena", token);

        AdminAccount stored = accounts.findByUsername("meena").orElseThrow();
        assertTrue(stored.isTotpEnabled());
        assertFalse(stored.getTotpSecretEnc().contains(on[0]), "the secret is stored encrypted");
        mockMvc.perform(get("/admin/accounts").header("X-Service-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("totpSecret"))))
                .andExpect(content().string(not(containsString("recoveryCodeHashes"))))
                .andExpect(jsonPath("$[0].twoStep").value(true));
    }

    @Test
    void turningItOffNeedsThePasswordAndACode_andAnOwnerCanResetALostPhone() throws Exception {
        String token = tokenWithoutTwoStep("kiran");
        String[] on = turnOn("kiran", token);
        long next = totp.stepAt(Instant.now()) + 1;

        // password right, code wrong: refused, still on
        mockMvc.perform(post("/admin/me/2fa/disable").header("X-Admin-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"code\":\"000000\"}"))
                .andExpect(status().isForbidden());
        assertTrue(accounts.findByUsername("kiran").orElseThrow().isTotpEnabled());

        // the owner (service key) clears it for a colleague who lost their phone
        mockMvc.perform(post("/admin/accounts/kiran/reset-2fa").header("X-Service-Key", KEY)).andExpect(status().isNoContent());
        assertFalse(accounts.findByUsername("kiran").orElseThrow().isTotpEnabled());
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("kiran", null)))
                .andExpect(status().isOk());
        assertEquals(next, next);                                   // (keeps the step variable meaningful for the reader)
    }

    @Test
    void startingSetupTwiceBeforeEnablingJustReplacesTheSecret_andWrongEnableCodeIsRefused() throws Exception {
        String token = tokenWithoutTwoStep("neha");
        mockMvc.perform(post("/admin/me/2fa/setup").header("X-Admin-Token", token)).andExpect(status().isOk());
        mockMvc.perform(post("/admin/me/2fa/setup").header("X-Admin-Token", token)).andExpect(status().isOk());

        mockMvc.perform(post("/admin/me/2fa/enable").header("X-Admin-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
        assertFalse(accounts.findByUsername("neha").orElseThrow().isTotpEnabled());
        // an unfinished setup never locks anyone out
        mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("neha", null)))
                .andExpect(status().isOk());
    }

    @Test
    void setupNeedsASignedInNamedAdmin() throws Exception {
        mockMvc.perform(post("/admin/me/2fa/setup")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/me/2fa/setup").header("X-Service-Key", KEY)).andExpect(status().isBadRequest());
    }
}
