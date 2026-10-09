package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only the owner manages a wishlist link; anyone holding the token can view it, and revoking kills it. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WishlistShareSecurityTest {
    private static final long OWNER = 9876500061L;
    private static final long OTHER = 9876500062L;
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

    private String session(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    @Test
    void creatingAndRevokingALinkNeedsTheOwnerSession() throws Exception {
        mockMvc.perform(post("/wishlist/share").param("phno", String.valueOf(OWNER))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/wishlist/share").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OTHER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/wishlist/share").param("phno", String.valueOf(OWNER))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/wishlist/share").param("phno", String.valueOf(OWNER))).andExpect(status().isUnauthorized());
    }

    @Test
    void sharedViewIsPublicWhileTheLinkLivesAndGoneOnceRevoked() throws Exception {
        Product p = new Product();
        p.setProductId(7);
        p.setProductName("Shared soap");
        p.setProductPrice(25);
        p.setProductStock(3);
        when(productClient.getProductById(7)).thenReturn(p);
        mockMvc.perform(post("/wishlist/self/add").param("phno", String.valueOf(OWNER)).param("productId", "7")
                .header("X-Customer-Token", session(OWNER))).andExpect(status().isOk());

        String body = mockMvc.perform(post("/wishlist/share").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");

        // No credentials at all, and the response never mentions the owner phone number.
        mockMvc.perform(get("/wishlist/shared/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Shared soap"))
                .andExpect(content().string(not(containsString(String.valueOf(OWNER)))));

        // Two opens so far (the one above and this one) - the owner sees the count, the public view never does.
        mockMvc.perform(get("/wishlist/shared/" + token)).andExpect(status().isOk());
        mockMvc.perform(get("/wishlist/share").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(2))
                .andExpect(jsonPath("$.token").value(token));

        mockMvc.perform(delete("/wishlist/share").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/wishlist/shared/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void anInvalidTokenIs404AndTheStaticPageIsPublic() throws Exception {
        mockMvc.perform(get("/wishlist/shared/nope")).andExpect(status().isNotFound());
        mockMvc.perform(get("/wishlist.html")).andExpect(status().isOk());
    }

    @Test
    void theServiceKeyCanAlsoManageALink() throws Exception {
        mockMvc.perform(get("/wishlist/share").param("phno", String.valueOf(OTHER)).header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(false));
    }
}
