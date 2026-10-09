package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anyone can read the FAQ; only the service key (or a MANAGER admin) can change it. Real H2 database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqSecurityTest {
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
    void theFaqIsReadableWithoutCredentials() throws Exception {
        mockMvc.perform(get("/faq")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void changingTheFaqNeedsTheServiceKeyNotACustomerSession() throws Exception {
        String body = "{\"question\":\"Q?\",\"answer\":\"A.\",\"sortOrder\":1}";
        mockMvc.perform(post("/faq/save").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/faq/save").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Customer-Token", customerAuthService.issueSession(9876500071L).token())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/faq/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCanAddThenDeleteAnEntryAndItShowsInThePublicList() throws Exception {
        String body = "{\"question\":\"Do you ship abroad?\",\"answer\":\"Not yet.\",\"sortOrder\":999}";
        String saved = mockMvc.perform(post("/faq/save").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("Do you ship abroad?"))
                .andReturn().getResponse().getContentAsString();
        String id = saved.replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(get("/faq")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")].answer").value("Not yet."));

        mockMvc.perform(delete("/faq/" + id).header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
        mockMvc.perform(delete("/faq/" + id).header("X-Service-Key", VALID_KEY)).andExpect(status().isNotFound());
    }

    @Test
    void aBlankQuestionIsABadRequest() throws Exception {
        mockMvc.perform(post("/faq/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \",\"answer\":\"A.\"}").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
