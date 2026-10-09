package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The logged-out order tracker shows the carrier and tracking number once an order has shipped (for its progress
 * line) and nothing before that - and still never the address, items or phone number.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuestOrderSummaryShipmentTest {
    private static final long PHNO = 9876500091L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CartRepository orders;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private long save(OrderStatus status, String carrier, String trackingNumber) {
        Cart cart = new Cart();
        cart.setCustomerName("Buyer");
        cart.setCustomerPhno(PHNO);
        cart.setStatus(status);
        cart.setPaymentMethod(PaymentMethod.CASH);
        cart.setTotalPrice(50);
        cart.setCarrier(carrier);
        cart.setTrackingNumber(trackingNumber);
        return orders.save(cart).getOrderId();
    }

    @Test
    void aShippedOrderShowsItsCarrierAndTrackingNumber() throws Exception {
        long id = save(OrderStatus.SHIPPED, "Delhivery", "DL123");

        mockMvc.perform(get("/cart/" + id + "/summary").param("phno", String.valueOf(PHNO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.carrier").value("Delhivery"))
                .andExpect(jsonPath("$.trackingNumber").value("DL123"))
                .andExpect(jsonPath("$.customerPhno").doesNotExist())
                .andExpect(jsonPath("$.orderItems").doesNotExist());
    }

    @Test
    void anOrderNotYetShippedHasNoShipmentFields() throws Exception {
        long id = save(OrderStatus.PLACED, null, null);

        mockMvc.perform(get("/cart/" + id + "/summary").param("phno", String.valueOf(PHNO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.trackingNumber").value(org.hamcrest.Matchers.nullValue()));
    }
}
