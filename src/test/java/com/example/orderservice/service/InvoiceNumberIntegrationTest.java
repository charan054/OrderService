package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.kafka.OrderKafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The locked counter query and the per-year row against a real (H2) database. */
@SpringBootTest
@ActiveProfiles("test")
class InvoiceNumberIntegrationTest {
    @Autowired
    private InvoiceNumberService invoiceNumbers;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @Test
    void consecutiveOrdersGetConsecutiveNumbers() {
        Cart first = new Cart();
        Cart second = new Cart();

        invoiceNumbers.assign(first);
        invoiceNumbers.assign(second);

        assertTrue(first.getInvoiceNumber().matches("CM/\\d{4}-\\d{2}/\\d{6}"), first.getInvoiceNumber());
        assertNotEquals(first.getInvoiceNumber(), second.getInvoiceNumber());
        long a = Long.parseLong(first.getInvoiceNumber().substring(first.getInvoiceNumber().lastIndexOf('/') + 1));
        long b = Long.parseLong(second.getInvoiceNumber().substring(second.getInvoiceNumber().lastIndexOf('/') + 1));
        assertEquals(a + 1, b);
    }
}
