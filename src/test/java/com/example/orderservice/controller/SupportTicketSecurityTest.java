package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customers open and follow their own support requests; the staff queue, replies and resolving need the service key. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportTicketSecurityTest {
    private static final String VALID_KEY = "test-service-key"; // matches application-test.properties
    private static final long CUSTOMER = 9876500061L;
    private static final long OTHER = 9876500062L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerAuthService customerAuthService;
    @Autowired
    private CartRepository orders;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private String session(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    private long deliveredOrder(long phno) {
        Cart c = new Cart();
        c.setCustomerName("Buyer");
        c.setCustomerPhno(phno);
        c.setStatus(OrderStatus.DELIVERED);
        c.setPaymentMethod(PaymentMethod.CASH);
        OrderItem item = new OrderItem();
        item.setProductId(1);
        item.setProductQuantity(1);
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        c.setOrderItems(items);
        return orders.save(c).getOrderId();
    }

    private String body(long phno, long orderId) {
        return "{\"customerPhno\":" + phno + ",\"orderId\":" + orderId + ",\"category\":\"DAMAGED\",\"message\":\"The box was crushed\"}";
    }

    @Test
    void aCustomerOpensAndFollowsTheirOwnRequestButNotSomeoneElses() throws Exception {
        long orderId = deliveredOrder(CUSTOMER);
        String token = session(CUSTOMER);

        mockMvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body(CUSTOMER, orderId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body(OTHER, orderId))
                        .header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body(CUSTOMER, orderId))
                        .header("X-Customer-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.messages[0].body").value("The box was crushed"));
        mockMvc.perform(get("/support/tickets/mine").param("phno", String.valueOf(CUSTOMER)).header("X-Customer-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticket.orderId").value(orderId));
        mockMvc.perform(get("/support/tickets/mine").param("phno", String.valueOf(OTHER)).header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherCustomersOrderIsNotFound() throws Exception {
        long othersOrder = deliveredOrder(OTHER);

        mockMvc.perform(post("/support/tickets").contentType(MediaType.APPLICATION_JSON).content(body(CUSTOMER, othersOrder))
                        .header("X-Customer-Token", session(CUSTOMER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theStaffSideIsServiceKeyOnly() throws Exception {
        String token = session(CUSTOMER);
        mockMvc.perform(get("/support/admin/tickets").header("X-Customer-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(post("/support/admin/tickets/1/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello there\"}").header("X-Customer-Token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/support/admin/tickets/1/resolve").header("X-Customer-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/support/admin/tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/support/admin/tickets").header("X-Service-Key", VALID_KEY)).andExpect(status().isOk());
    }
}
