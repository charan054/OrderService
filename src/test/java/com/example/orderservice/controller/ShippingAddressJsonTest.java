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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The storefront and the admin dashboard both send and read the default flag as "isDefault". It used to be serialised as
 * "default" (Lombok's name for isDefault()/setDefault()), so a ticked Make default box was silently ignored. Real H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingAddressJsonTest {
    private static final long OWNER = 9876500111L;
    private static final long OTHER = 9876500112L;

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

    private String body(long phno, String line1, String defaultField) {
        return "{\"customerPhno\":" + phno + ",\"line1\":\"" + line1 + "\",\"city\":\"Pune\",\"state\":\"MH\",\"pincode\":\"411001\"," + defaultField + "}";
    }

    private long add(long phno, String line1, String defaultField) throws Exception {
        String json = mockMvc.perform(post("/addresses/self/add").contentType(MediaType.APPLICATION_JSON)
                        .content(body(phno, line1, defaultField)).header("X-Customer-Token", session(phno)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void isDefaultIsAcceptedAndReturnedAndTheOldDefaultKeyStillWorks() throws Exception {
        long first = add(OWNER, "1 First Rd", "\"isDefault\":true");
        mockMvc.perform(get("/addresses/byphno").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + first + ")].isDefault").value(true))
                .andExpect(jsonPath("$[?(@.id==" + first + ")].default").isEmpty());

        // Saving another as default (via the legacy key) takes the default away from the first.
        long second = add(OWNER, "2 Second Rd", "\"default\":true");
        mockMvc.perform(get("/addresses/byphno").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(jsonPath("$[?(@.id==" + first + ")].isDefault").value(false))
                .andExpect(jsonPath("$[?(@.id==" + second + ")].isDefault").value(true));
    }

    @Test
    void makeDefaultSwitchesTheDefaultAndIsOwnerOnly() throws Exception {
        long a = add(OWNER, "A Rd", "\"isDefault\":true");
        long b = add(OWNER, "B Rd", "\"isDefault\":false");

        mockMvc.perform(put("/addresses/self/default").param("phno", String.valueOf(OWNER)).param("addressId", String.valueOf(b))
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mockMvc.perform(get("/addresses/byphno").param("phno", String.valueOf(OWNER)).header("X-Customer-Token", session(OWNER)))
                .andExpect(jsonPath("$[?(@.id==" + a + ")].isDefault").value(false))
                .andExpect(jsonPath("$[?(@.id==" + b + ")].isDefault").value(true));

        mockMvc.perform(put("/addresses/self/default").param("phno", String.valueOf(OWNER)).param("addressId", String.valueOf(a)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/addresses/self/default").param("phno", String.valueOf(OWNER)).param("addressId", String.valueOf(a))
                        .header("X-Customer-Token", session(OTHER)))
                .andExpect(status().isForbidden());
        // Another customer cannot make MY address their default by guessing its id either.
        mockMvc.perform(put("/addresses/self/default").param("phno", String.valueOf(OTHER)).param("addressId", String.valueOf(a))
                        .header("X-Customer-Token", session(OTHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void editingAnAddressByIdUpdatesItInPlace() throws Exception {
        long id = add(OWNER, "Old Rd", "\"isDefault\":false");
        mockMvc.perform(post("/addresses/self/add").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id + ",\"customerPhno\":" + OWNER + ",\"line1\":\"New Rd\",\"line2\":\"Flat 4\",\"city\":\"Pune\",\"state\":\"MH\",\"pincode\":\"411002\",\"isDefault\":false}")
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.line1").value("New Rd"))
                .andExpect(jsonPath("$.line2").value("Flat 4"));
    }

    @Test
    void aBadPincodeIsA400() throws Exception {
        mockMvc.perform(post("/addresses/self/add").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerPhno\":" + OWNER + ",\"line1\":\"x\",\"city\":\"c\",\"state\":\"s\",\"pincode\":\"12\"}")
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isBadRequest());
    }
}
