package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.repository.AuditLogRepository;
import com.example.orderservice.repository.GiftCardRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.service.AdminAuthService;
import com.example.orderservice.service.CustomerAuthService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may mint, list, void and redeem gift cards - and that a code never lands in a URL or the audit log. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GiftCardSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final String PASSWORD = "correct horse battery";
    private static final long ASHA = 9876500211L;
    private static final long RAVI = 9876500212L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private CustomerAuthService customerAuthService;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private AdminSessionRepository adminSessions;
    @Autowired
    private AuditLogRepository auditLog;
    @Autowired
    private GiftCardRepository cards;
    @Autowired
    private StoreCreditAccountRepository walletAccounts;
    @Autowired
    private StoreCreditTransactionRepository walletTransactions;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @BeforeEach
    void reset() {
        adminSessions.deleteAll();
        adminAccounts.deleteAll();
        auditLog.deleteAll();
        cards.deleteAll();
        walletTransactions.deleteAll();
        walletAccounts.deleteAll();
    }

    private String adminToken(String username, AdminRole role) {
        adminAuthService.create(new NewAdminAccount(username, PASSWORD, role), "service-key");
        return adminAuthService.login(username, PASSWORD).token();
    }

    private String customerToken(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    private static String mintBody(int count, double amount) {
        return "{\"count\":" + count + ",\"amount\":" + amount + ",\"note\":\"test\"}";
    }

    private static String redeemBody(long phno, String code) {
        return "{\"phno\":" + phno + ",\"code\":\"" + code + "\"}";
    }

    // Mints one card as the service key and returns its plain code.
    private String mintCode() throws Exception {
        String body = mockMvc.perform(post("/giftcards/mint").header("X-Service-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(mintBody(1, 250)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.cards[0].code");
    }

    @Test
    void onlyAnOwnerOrTheServiceKeyCanMintListAndVoid() throws Exception {
        String owner = adminToken("owner1", AdminRole.OWNER);
        String manager = adminToken("manager1", AdminRole.MANAGER);
        String support = adminToken("support1", AdminRole.SUPPORT);
        String customer = customerToken(ASHA);

        mockMvc.perform(post("/giftcards/mint").contentType(MediaType.APPLICATION_JSON).content(mintBody(1, 100)))
                .andExpect(status().isUnauthorized());
        for (String token : new String[]{manager, support}) {
            mockMvc.perform(post("/giftcards/mint").header("X-Admin-Token", token).contentType(MediaType.APPLICATION_JSON).content(mintBody(1, 100)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/giftcards").header("X-Admin-Token", token)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/giftcards/mint").header("X-Customer-Token", customer).contentType(MediaType.APPLICATION_JSON).content(mintBody(1, 100)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/giftcards").header("X-Customer-Token", customer)).andExpect(status().isForbidden());
        assertThat(cards.count()).isZero();

        mockMvc.perform(post("/giftcards/mint").header("X-Admin-Token", owner).contentType(MediaType.APPLICATION_JSON).content(mintBody(2, 100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.totalValue").value(200.0));
        long id = cards.findAll().get(0).getId();
        mockMvc.perform(get("/giftcards").header("X-Admin-Token", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingCards").value(2))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("codeHash"))));
        mockMvc.perform(put("/giftcards/" + id + "/void").header("X-Admin-Token", manager)).andExpect(status().isForbidden());
        mockMvc.perform(put("/giftcards/" + id + "/void").header("X-Admin-Token", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
        mockMvc.perform(get("/giftcards").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }

    @Test
    void aCustomerRedeemsIntoTheirOwnWalletOnly() throws Exception {
        String code = mintCode();
        String asha = customerToken(ASHA);

        mockMvc.perform(post("/giftcards/redeem").contentType(MediaType.APPLICATION_JSON).content(redeemBody(ASHA, code)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/giftcards/redeem").header("X-Customer-Token", asha).contentType(MediaType.APPLICATION_JSON).content(redeemBody(RAVI, code)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/giftcards/redeem").header("X-Customer-Token", asha).contentType(MediaType.APPLICATION_JSON).content(redeemBody(ASHA, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(250.0))
                .andExpect(jsonPath("$.balance").value(250.0));
        mockMvc.perform(get("/storecredit/byphno").param("phno", String.valueOf(ASHA)).header("X-Customer-Token", asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.0))
                .andExpect(jsonPath("$.transactions[0].type").value("GIFT_CARD"));
        mockMvc.perform(post("/giftcards/redeem").header("X-Customer-Token", asha).contentType(MediaType.APPLICATION_JSON).content(redeemBody(ASHA, code)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("already been used")));
    }

    @Test
    void aWrongCodeIsA400AndCostsNothing() throws Exception {
        String asha = customerToken(ASHA);

        mockMvc.perform(post("/giftcards/redeem").header("X-Customer-Token", asha).contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(ASHA, "GC-ABCD-EFGH-JKMN-PQRS")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("isn't valid")));
        mockMvc.perform(post("/giftcards/redeem").header("X-Customer-Token", asha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":" + ASHA + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCodeInTheUrlIsIgnoredSoItCannotLeakIntoLogs() throws Exception {
        String code = mintCode();
        String asha = customerToken(ASHA);

        mockMvc.perform(post("/giftcards/redeem").param("phno", String.valueOf(ASHA)).param("code", code)
                        .header("X-Customer-Token", asha))
                .andExpect(status().isBadRequest());
        assertThat(cards.findAll()).allSatisfy(c -> assertThat(c.getRedeemedAt()).isNull());
    }

    @Test
    void aManagerOrTheServiceKeyCanRedeemForACustomerButSupportCannot() throws Exception {
        String manager = adminToken("manager1", AdminRole.MANAGER);
        String support = adminToken("support1", AdminRole.SUPPORT);
        String first = mintCode();
        String second = mintCode();

        mockMvc.perform(post("/giftcards/redeem").header("X-Admin-Token", support).contentType(MediaType.APPLICATION_JSON).content(redeemBody(ASHA, first)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/giftcards/redeem").header("X-Admin-Token", manager).contentType(MediaType.APPLICATION_JSON).content(redeemBody(ASHA, first)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/giftcards/redeem").header("X-Service-Key", VALID_KEY).contentType(MediaType.APPLICATION_JSON).content(redeemBody(RAVI, second)))
                .andExpect(status().isOk());
    }

    @Test
    void theAuditLogNamesWhoMintedButNeverHoldsACode() throws Exception {
        String owner = adminToken("owner1", AdminRole.OWNER);
        String body = mockMvc.perform(post("/giftcards/mint").header("X-Admin-Token", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(mintBody(1, 100)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.cards[0].code");

        assertThat(auditLog.findAll()).anySatisfy(e -> {
            assertThat(e.getActor()).isEqualTo("owner1");
            assertThat(e.getPath()).isEqualTo("/giftcards/mint");
        });
        assertThat(auditLog.findAll().toString()).doesNotContain(code).doesNotContain(code.replace("-", ""));
        assertThat(cards.findAll()).singleElement().satisfies(c -> assertThat(c.getCreatedBy()).isEqualTo("owner1"));
    }
}
