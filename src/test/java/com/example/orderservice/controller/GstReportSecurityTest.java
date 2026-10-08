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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The GST report is for the owner's side of the business: service key, MANAGER or OWNER - never a customer or SUPPORT. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GstReportSecurityTest {
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
    void theReportNeedsTheServiceKeyOrAManagerOrOwner() throws Exception {
        for (String path : new String[]{"/gst/report", "/gst/report/export"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("X-Customer-Token", customerAuthService.issueSession(9876500121L).token()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("X-Admin-Token", tokenFor("support-" + path.length(), AdminRole.SUPPORT)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("X-Admin-Token", tokenFor("manager-" + path.length(), AdminRole.MANAGER)))
                    .andExpect(status().isOk());
            mockMvc.perform(get(path).header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        }
    }

    @Test
    void theJsonReportDefaultsToThisMonthAndTheCsvIsADownload() throws Exception {
        mockMvc.perform(get("/gst/report").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.net.totalTax").exists());
        mockMvc.perform(get("/gst/report/export").param("from", "2026-10-01").param("to", "2026-10-31").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("gst-report.csv")))
                .andExpect(content().string(containsString("month,type,placeOfSupply")));
    }

    @Test
    void thePdfFollowsTheSameAccessRulesAndIsADownload() throws Exception {
        mockMvc.perform(get("/gst/report/pdf")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/gst/report/pdf").header("X-Customer-Token", customerAuthService.issueSession(9876500122L).token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/gst/report/pdf").header("X-Admin-Token", tokenFor("support-pdf", AdminRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/gst/report/pdf").param("from", "2026-10-01").param("to", "2026-10-31").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("gst-report-2026-10-01-to-2026-10-31.pdf")));
    }

    @Test
    void aBackwardsOrOversizedRangeIsA400() throws Exception {
        mockMvc.perform(get("/gst/report").param("from", "2026-10-31").param("to", "2026-10-01").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/gst/report").param("from", "2024-01-01").param("to", "2026-10-01").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/gst/report").param("from", "not-a-date").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
