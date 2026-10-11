package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.ModerationReviewsResult;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /cart/attention: who may call it, and that it counts what is really in the database. Real security chain + H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NeedsAttentionApiTest {
    private static final String VALID_KEY = "test-service-key";

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerAuthService customerAuthService;
    @Autowired private SupportTicketRepository tickets;
    @Autowired private ProductQuestionRepository questions;
    @Autowired private CartRepository carts;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private PhonepeClient phonepeClient;
    @MockitoBean private OrderKafkaProducer orderKafkaProducer;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode attention() throws Exception {
        String body = mockMvc.perform(get("/cart/attention").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static int count(JsonNode result, String key) {
        for (JsonNode item : result.get("items")) {
            if (key.equals(item.get("key").asText())) {
                return item.get("count").asInt();
            }
        }
        throw new AssertionError("no row " + key);
    }

    @Test
    void onlyTheServiceKeyOrAnAdminMayAsk() throws Exception {
        mockMvc.perform(get("/cart/attention")).andExpect(status().isUnauthorized());
        // A signed-in shopper is not an admin.
        String token = customerAuthService.issueSession(9200000001L).token();
        mockMvc.perform(get("/cart/attention").header("X-Customer-Token", token)).andExpect(status().isForbidden());
    }

    @Test
    void itCountsWhatIsReallyWaiting() throws Exception {
        when(productClient.findAll()).thenReturn(new ArrayList<>());
        when(productClient.getFlaggedReviews(anyString(), anyInt(), anyInt())).thenReturn(new ModerationReviewsResult(List.of()));
        JsonNode before = attention();

        SupportTicket open = new SupportTicket();
        open.setCustomerPhno(9200000002L);
        open.setOrderId(1);
        open.setCategory(SupportTicket.Category.DAMAGED);
        open.setStatus(SupportTicket.Status.OPEN);
        open.setCreatedAt(Instant.now());
        tickets.save(open);
        SupportTicket answered = new SupportTicket();
        answered.setCustomerPhno(9200000002L);
        answered.setOrderId(1);
        answered.setCategory(SupportTicket.Category.OTHER);
        answered.setStatus(SupportTicket.Status.ANSWERED);
        answered.setCreatedAt(Instant.now());
        tickets.save(answered);
        ProductQuestion q = new ProductQuestion();
        q.setProductId(1);
        q.setAskerPhno(9200000002L);
        q.setQuestion("Does it come in blue?");
        q.setCreatedAt(Instant.now());
        questions.save(q);
        Cart late = new Cart();
        late.setCustomerName("Late Payer");
        late.setCustomerPhno(9200000002L);
        late.setOrderItems(new ArrayList<>());
        late.setStatus(OrderStatus.PENDING_PAYMENT);
        late.setPaymentMethod(PaymentMethod.PHONEPE);
        late.setPaymentDeadline(Instant.now().minus(30, ChronoUnit.MINUTES));
        late = carts.save(late);
        Cart waiting = new Cart();
        waiting.setCustomerName("Still Within Time");
        waiting.setCustomerPhno(9200000002L);
        waiting.setOrderItems(new ArrayList<>());
        waiting.setStatus(OrderStatus.PENDING_PAYMENT);
        waiting.setPaymentMethod(PaymentMethod.PHONEPE);
        waiting.setPaymentDeadline(Instant.now().plus(10, ChronoUnit.MINUTES));
        carts.save(waiting);

        JsonNode after = attention();

        // Only a request still waiting on the store counts (not an ANSWERED one), and only an order past its deadline.
        assertEquals(count(before, "supportTickets") + 1, count(after, "supportTickets"));
        assertEquals(count(before, "unansweredQuestions") + 1, count(after, "unansweredQuestions"));
        assertEquals(count(before, "stuckPayments") + 1, count(after, "stuckPayments"));
        assertEquals(before.get("total").asInt() + 3, after.get("total").asInt());
        boolean listed = false;
        for (JsonNode item : after.get("items")) {
            if ("stuckPayments".equals(item.get("key").asText())) {
                for (JsonNode id : item.get("orderIds")) {
                    listed |= id.asLong() == late.getOrderId();
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(listed, "the overdue order should be listed");
    }

    @Test
    void ifProductServiceIsDownTheRestStillAnswers() throws Exception {
        when(productClient.findAll()).thenThrow(new RuntimeException("connection refused"));
        when(productClient.getFlaggedReviews(anyString(), anyInt(), anyInt())).thenThrow(new RuntimeException("connection refused"));

        mockMvc.perform(get("/cart/attention").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key == 'lowStock')].unavailable").value(true))
                .andExpect(jsonPath("$.items[?(@.key == 'flaggedReviews')].unavailable").value(true))
                .andExpect(jsonPath("$.items[?(@.key == 'supportTickets')].unavailable").value(false));
    }
}
