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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The storefront groups options of one product (sizes, packs) into a single card from variantGroup / variantLabel, so
 * the catalog search has to carry both through; options are ordinary products otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductVariantPassThroughTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private static Product product(int id, String name, double price, String group, String label) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName(name);
        p.setProductCategory("personal care");
        p.setProductPrice(price);
        p.setProductStock(5);
        p.setVariantGroup(group);
        p.setVariantLabel(label);
        return p;
    }

    @Test
    void searchCarriesTheOptionFieldsAndLeavesOrdinaryProductsWithoutThem() throws Exception {
        when(productClient.search(any(), any(), anyInt(), anyInt())).thenReturn(new ProductSearchResult(List.of(
                product(1, "Dove Shampoo - 340 ml", 200, "dove-shampoo", "340 ml"),
                product(2, "Dove Shampoo - 1 L", 500, "dove-shampoo", "1 L"),
                product(3, "Plain Soap", 40, null, null))));

        mockMvc.perform(get("/cart/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].variantGroup").value("dove-shampoo"))
                .andExpect(jsonPath("$[0].variantLabel").value("340 ml"))
                .andExpect(jsonPath("$[1].variantGroup").value("dove-shampoo"))
                .andExpect(jsonPath("$[1].variantLabel").value("1 L"))
                .andExpect(jsonPath("$[2].variantGroup").doesNotExist())
                .andExpect(jsonPath("$[2].productId").value(3));
    }
}
