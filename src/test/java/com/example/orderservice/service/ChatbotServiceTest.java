package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Faq;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentAttempt;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.FaqRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatbotServiceTest {
    private static final long ME = 9876543210L;

    private CartRepository orders;
    private FaqRepository faqs;
    private PaymentAttemptService attempts;
    private OrderService orderService;
    private ChatbotService bot;

    @BeforeEach
    void setUp() {
        orders = mock(CartRepository.class);
        faqs = mock(FaqRepository.class);
        attempts = mock(PaymentAttemptService.class);
        orderService = mock(OrderService.class);
        when(orderService.getCancelPolicy()).thenReturn(Map.of("freeMinutes", 10, "lockMinutes", 0));
        when(faqs.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(attempts.needingAttention()).thenReturn(List.of());
        bot = new ChatbotService(orders, faqs, attempts, orderService);
    }

    private static Cart order(long id, OrderStatus status) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(ME);
        c.setStatus(status);
        return c;
    }

    private void mine(Cart... carts) {
        when(orders.findBycustomerPhno(ME)).thenReturn(List.of(carts));
    }

    @Test
    void whereIsMyOrderAnswersAboutTheLatestOrderWithCarrierAndExpectedDate() {
        Cart shipped = order(12, OrderStatus.SHIPPED);
        shipped.setCarrier("Delhivery");
        shipped.setTrackingNumber("DL123");
        shipped.setExpectedDeliveryDate(LocalDate.parse("2026-10-30"));
        mine(order(10, OrderStatus.DELIVERED), shipped);

        String text = bot.ask(ME, "Where is my order?").text();

        assertTrue(text.contains("Order #12 is shipped"), text);
        assertTrue(text.contains("Delhivery") && text.contains("DL123") && text.contains("2026-10-30"), text);
        assertTrue(text.contains("order #10"), text);
    }

    @Test
    void aGeneralDeliveryQuestionIsNotTreatedAsAnOrderLookup() {
        Faq faq = new Faq();
        faq.setQuestion("Do you accept cash on delivery?");
        faq.setAnswer("Yes, cash on delivery is available on orders up to Rs. 2000.");
        when(faqs.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(faq));
        mine(order(12, OrderStatus.PLACED));

        assertTrue(bot.ask(ME, "do you accept cash on delivery").text().startsWith("Yes, cash on delivery"));
        assertTrue(bot.ask(ME, "when will my order arrive").text().contains("Order #12"));
    }

    @Test
    void anOrderNumberPicksThatOrderAndSomeoneElsesOrderIsNotFound() {
        mine(order(12, OrderStatus.PLACED), order(10, OrderStatus.DELIVERED));

        assertTrue(bot.ask(ME, "status of order #10").text().contains("Order #10 is delivered"));
        assertTrue(bot.ask(ME, "status of order #99").text().contains("can't find that order"));
    }

    @Test
    void cancelExplainsWhatIsPossibleForTheOrdersStatus() {
        mine(order(12, OrderStatus.PLACED));
        assertTrue(bot.ask(ME, "I want to cancel").text().contains("can be cancelled"));

        mine(order(12, OrderStatus.SHIPPED));
        assertTrue(bot.ask(ME, "cancel order 12").text().contains("already shipped"));
    }

    @Test
    void refundStatusShowsWhatWasRefundedAndWhere() {
        Cart cancelled = order(12, OrderStatus.CANCELLED);
        cancelled.setPaid(true);
        cancelled.setRefundedAmount(250.5);
        cancelled.setRefundDestination("your PhonePe account");
        mine(cancelled);

        String text = bot.ask(ME, "where is my refund").text();

        assertTrue(text.contains("Refunded: Rs. 250.50 to your PhonePe account"), text);
    }

    @Test
    void aChargeWithNoOrderIsRecognisedForThisCustomerOnly() {
        mine(order(12, OrderStatus.PLACED));
        PaymentAttempt mineAttempt = new PaymentAttempt();
        mineAttempt.setCustomerPhno(ME);
        mineAttempt.setAmount(300);
        mineAttempt.setCreatedAt(Instant.parse("2026-10-20T10:00:00Z"));
        PaymentAttempt other = new PaymentAttempt();
        other.setCustomerPhno(9000000001L);
        other.setAmount(999);
        when(attempts.needingAttention()).thenReturn(List.of(other, mineAttempt));

        String text = bot.ask(ME, "money was deducted but no order").text();

        assertTrue(text.contains("Rs. 300.00") && !text.contains("999"), text);

        when(attempts.needingAttention()).thenReturn(List.of(other));
        assertTrue(bot.ask(ME, "money was deducted but no order").text().contains("don't see any payment of yours that is stuck"));
    }

    @Test
    void loyaltyPointsComeFromTheAccount() {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setCustomerPhno(ME);
        account.setPointsBalance(120);
        when(orderService.getLoyaltyAccount(ME)).thenReturn(account);
        mine();

        assertTrue(bot.ask(ME, "how many points do I have").text().contains("120 loyalty point"));
    }

    @Test
    void anUnknownQuestionIsAnsweredFromTheHelpEntriesOrPointsToSupport() {
        Faq faq = new Faq();
        faq.setQuestion("Do you ship to Hyderabad?");
        faq.setAnswer("Yes, we deliver across Hyderabad in two days.");
        when(faqs.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(faq));
        mine();

        assertEquals("Yes, we deliver across Hyderabad in two days.", bot.ask(ME, "do you cover hyderabad").text());
        assertTrue(bot.ask(ME, "purple elephants").text().contains("didn't understand"));
    }

    @Test
    void greetingsAndHumanRequestsGetTheMenuAndShortWordsMatchWholeWordsOnly() {
        mine();

        assertTrue(bot.ask(ME, "hi").text().startsWith("Hi!"));
        assertFalse(bot.ask(ME, "shipping charges?").text().startsWith("Hi!"));
        assertTrue(bot.ask(ME, "I want to talk to a human").text().contains("Report a problem"));
        assertFalse(bot.ask(ME, "hello").suggestions().isEmpty());
    }

    @Test
    void emptyAndHugeMessagesAreRefused() {
        assertThrows(ProductException.class, () -> bot.ask(ME, "  "));
        assertThrows(ProductException.class, () -> bot.ask(ME, "x".repeat(ChatbotService.MAX_MESSAGE + 1)));
    }
}
