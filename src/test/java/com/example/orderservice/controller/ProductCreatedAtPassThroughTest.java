package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.kafka.OrderKafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The storefront New shelf and badge depend on createdAt travelling ProductService -> OrderService -> browser, so
 * the DTO has to read ProductService's ISO timestamp and write it back out, and tolerate products without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductCreatedAtPassThroughTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @Test
    void readsProductServiceTimestampAndTreatsAMissingOneAsNull() throws Exception {
        Product withDate = objectMapper.readValue(
                "{\"productId\":1,\"productName\":\"A\",\"createdAt\":\"2026-10-09T10:15:30.123456Z\"}", Product.class);
        Product withoutDate = objectMapper.readValue("{\"productId\":2,\"productName\":\"B\",\"createdAt\":null}", Product.class);
        Product absent = objectMapper.readValue("{\"productId\":3,\"productName\":\"C\"}", Product.class);

        assertEquals(Instant.parse("2026-10-09T10:15:30.123456Z"), withDate.getCreatedAt());
        assertNull(withoutDate.getCreatedAt());
        assertNull(absent.getCreatedAt());
    }

    @Test
    void searchCarriesCreatedAtToTheStorefrontAsAnIsoString() throws Exception {
        Product fresh = new Product();
        fresh.setProductId(1);
        fresh.setProductName("Fresh");
        fresh.setProductPrice(10);
        fresh.setProductStock(2);
        fresh.setCreatedAt(Instant.parse("2026-10-09T10:00:00Z"));
        Product old = new Product();
        old.setProductId(2);
        old.setProductName("Old");
        old.setProductPrice(10);
        old.setProductStock(2);
        when(productClient.search(any(), any(), anyInt(), anyInt())).thenReturn(new ProductSearchResult(List.of(fresh, old)));

        mockMvc.perform(get("/cart/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$[1].createdAt").doesNotExist());
    }
}
