package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CodOverride;
import com.example.orderservice.entity.CouponRedemption;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.GiftCard;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.LoyaltyTransaction;
import com.example.orderservice.entity.LoyaltyTransactionType;
import com.example.orderservice.entity.OrderFeedback;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.entity.Referral;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.StoreCreditAccount;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.Subscription;
import com.example.orderservice.entity.SupportMessage;
import com.example.orderservice.entity.SupportTicket;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.entity.WishlistShare;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AccountDeletionCodeRepository;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CodOverrideRepository;
import com.example.orderservice.repository.CouponRedemptionRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.CustomerSessionRepository;
import com.example.orderservice.repository.GiftCardRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.LoyaltyTransactionRepository;
import com.example.orderservice.repository.OrderFeedbackRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.ReferralRepository;
import com.example.orderservice.repository.SavedCartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.repository.SubscriptionRepository;
import com.example.orderservice.repository.SupportMessageRepository;
import com.example.orderservice.repository.SupportTicketRepository;
import com.example.orderservice.repository.WishlistRepository;
import com.example.orderservice.repository.WishlistShareRepository;
import com.example.orderservice.service.CustomerAuthService;
import com.example.orderservice.service.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deleting your own account end to end: emailed code, what is erased, what is kept anonymised, and what blocks it. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionApiTest {
    private static final String VALID_KEY = "test-service-key";
    private static final Pattern CODE = Pattern.compile("code is (\\d{6})");

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerAuthService customerAuthService;
    @Autowired private CustomerAccountRepository accounts;
    @Autowired private CustomerSessionRepository sessions;
    @Autowired private AccountDeletionCodeRepository deletionCodes;
    @Autowired private CartRepository carts;
    @Autowired private ShippingAddressRepository addresses;
    @Autowired private WishlistRepository wishlist;
    @Autowired private WishlistShareRepository wishlistShares;
    @Autowired private StockWaitlistRepository waitlist;
    @Autowired private SavedCartRepository savedCarts;
    @Autowired private LoyaltyAccountRepository loyalty;
    @Autowired private LoyaltyTransactionRepository loyaltyTransactions;
    @Autowired private StoreCreditAccountRepository credit;
    @Autowired private StoreCreditTransactionRepository creditTransactions;
    @Autowired private ProductQuestionRepository questions;
    @Autowired private OrderFeedbackRepository feedback;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private SupportTicketRepository tickets;
    @Autowired private SupportMessageRepository messages;
    @Autowired private ReferralRepository referrals;
    @Autowired private CouponRedemptionRepository couponRedemptions;
    @Autowired private CodOverrideRepository codOverrides;
    @Autowired private GiftCardRepository giftCards;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private PhonepeClient phonepeClient;
    @MockitoBean private OrderKafkaProducer orderKafkaProducer;
    @MockitoBean private MailService mailService;

    // ---------- helpers ----------

    private String signedIn(long phno, String email) {
        CustomerAccount account = new CustomerAccount();
        account.setPhno(phno);
        account.setEmail(email);
        account.setVerifiedAt(Instant.now());
        accounts.save(account);
        return customerAuthService.issueSession(phno).token();
    }

    private Cart order(long phno, OrderStatus status, PaymentMethod method, boolean paid, Long addressId) {
        Cart cart = new Cart();
        cart.setCustomerName("Asha Rao");
        cart.setCustomerPhno(phno);
        cart.setOrderItems(new ArrayList<>());
        cart.setStatus(status);
        cart.setPaymentMethod(method);
        cart.setPaid(paid);
        cart.setShippingAddressId(addressId);
        cart.setUpiId("asha@upi");
        cart.setDeliveryNote("Leave with the guard, flat 4B");
        cart.setCancelNote("Changed my mind, call me on 9000000000");
        cart.setTotalPrice(100);
        return carts.save(cart);
    }

    private ShippingAddress address(long phno, String line1, String state) {
        ShippingAddress a = new ShippingAddress();
        a.setCustomerPhno(phno);
        a.setLabel("Home");
        a.setLine1(line1);
        a.setLine2("Near the park");
        a.setCity("Bengaluru");
        a.setState(state);
        a.setPincode("560001");
        a.setDefault(true);
        return addresses.save(a);
    }

    private String emailedCode() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService, atLeastOnce()).send(anyString(), eq("Confirm deleting your account"), body.capture());
        Matcher m = CODE.matcher(body.getValue());
        assertTrue(m.find(), "the email should carry the code");
        return m.group(1);
    }

    private void requestCode(String token) throws Exception {
        mockMvc.perform(post("/customer/account/delete/request").header("X-Customer-Token", token))
                .andExpect(status().isNoContent());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String code, boolean ack) throws Exception {
        return mockMvc.perform(post("/customer/account/delete/confirm").header("X-Customer-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\",\"acknowledgeForfeit\":%s}".formatted(code, ack)));
    }

    // ---------- who may call it ----------

    @Test
    void onlyASignedInCustomerCanUseIt() throws Exception {
        long phno = 9100000001L;
        signedIn(phno, "a1@example.com");

        mockMvc.perform(get("/customer/account/delete/preview")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/customer/account/delete/request")).andExpect(status().isUnauthorized());
        // The service key is not a customer: it cannot start, or confirm, someone's deletion.
        mockMvc.perform(get("/customer/account/delete/preview").header("X-Service-Key", VALID_KEY)).andExpect(status().isForbidden());
        mockMvc.perform(post("/customer/account/delete/request").header("X-Service-Key", VALID_KEY)).andExpect(status().isForbidden());
        mockMvc.perform(post("/customer/account/delete/confirm").header("X-Service-Key", VALID_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")).andExpect(status().isForbidden());
        assertTrue(accounts.findById(phno).isPresent());
    }

    // ---------- the happy path ----------

    @Test
    void confirmingWithTheEmailedCodeErasesPersonalDataAndKeepsAnonymisedOrders() throws Exception {
        long phno = 9100000002L;
        long other = 9100000003L;
        String token = signedIn(phno, "asha@example.com");
        signedIn(other, "other@example.com");

        ShippingAddress shippedTo = address(phno, "12 Park Street", "Karnataka");
        ShippingAddress unused = address(phno, "99 Old Lane", "Kerala");
        ShippingAddress othersAddress = address(other, "5 Other Road", "Goa");
        Cart delivered = order(phno, OrderStatus.DELIVERED, PaymentMethod.PHONEPE, true, shippedTo.getId());
        Cart cancelled = order(phno, OrderStatus.CANCELLED, PaymentMethod.PHONEPE, true, null);
        Cart othersOrder = order(other, OrderStatus.DELIVERED, PaymentMethod.PHONEPE, true, othersAddress.getId());

        Wishlist w = new Wishlist(); w.setCustomerPhno(phno); w.setProductId(7); wishlist.save(w);
        Wishlist ow = new Wishlist(); ow.setCustomerPhno(other); ow.setProductId(7); wishlist.save(ow);
        WishlistShare share = new WishlistShare(); share.setToken("tok-" + phno); share.setCustomerPhno(phno);
        share.setCreatedAt(Instant.now()); wishlistShares.save(share);
        StockWaitlist alert = new StockWaitlist(); alert.setCustomerPhno(phno); alert.setProductId(8); waitlist.save(alert);
        SavedCart saved = new SavedCart(); saved.setPhno(phno); saved.setUpdatedAt(Instant.now());
        SavedCart.Line line = new SavedCart.Line(); line.setProductId(7); line.setQuantity(2); saved.getLines().add(line);
        savedCarts.save(saved);
        LoyaltyAccount points = new LoyaltyAccount(); points.setCustomerPhno(phno); points.setPointsBalance(40); loyalty.save(points);
        LoyaltyTransaction pt = new LoyaltyTransaction(); pt.setCustomerPhno(phno); pt.setPoints(40);
        pt.setType(LoyaltyTransactionType.EARNED); pt.setTimestamp(Instant.now()); loyaltyTransactions.save(pt);
        StoreCreditAccount wallet = new StoreCreditAccount(); wallet.setPhno(phno); wallet.setBalance(25.5); credit.save(wallet);
        StoreCreditTransaction ct = new StoreCreditTransaction(); ct.setPhno(phno); ct.setType(StoreCreditTransaction.Type.ADJUSTED);
        ct.setAmount(25.5); ct.setBalanceAfter(25.5); ct.setCreatedAt(Instant.now()); creditTransactions.save(ct);
        ProductQuestion q = new ProductQuestion(); q.setProductId(7); q.setAskerPhno(phno); q.setQuestion("Is it waterproof?");
        q.setCreatedAt(Instant.now()); questions.save(q);
        OrderFeedback fb = new OrderFeedback(); fb.setOrderId(delivered.getOrderId()); fb.setCustomerPhno(phno); fb.setRating(5);
        fb.setComment("Lovely, call me on 9100000002"); fb.setCreatedAt(Instant.now()); feedback.save(fb);
        Subscription sub = new Subscription(); sub.setCustomerPhno(phno); sub.setCustomerName("Asha Rao"); sub.setProductId(7);
        sub.setQuantity(1); sub.setIntervalDays(30); sub.setStatus(Subscription.CANCELLED); sub.setCreatedAt(Instant.now()); subscriptions.save(sub);
        SupportTicket ticket = new SupportTicket(); ticket.setCustomerPhno(phno); ticket.setOrderId(delivered.getOrderId());
        ticket.setCategory(SupportTicket.Category.OTHER); ticket.setStatus(SupportTicket.Status.RESOLVED);
        ticket.setCreatedAt(Instant.now()); ticket = tickets.save(ticket);
        SupportMessage msg = new SupportMessage(); msg.setTicketId(ticket.getId()); msg.setAuthor(SupportMessage.Author.CUSTOMER);
        msg.setBody("My address is 12 Park Street"); msg.setCreatedAt(Instant.now()); messages.save(msg);
        Referral ref = new Referral(); ref.setReferrerPhno(other); ref.setRefereePhno(phno); ref.setCreatedAt(Instant.now()); referrals.save(ref);
        CouponRedemption redemption = new CouponRedemption(); redemption.setCouponCode("WELCOME"); redemption.setCustomerPhno(phno);
        redemption.setCount(1); couponRedemptions.save(redemption);
        CodOverride cod = new CodOverride(); cod.setPhno(phno); cod.setMode(CodOverride.Mode.ALLOW); cod.setUpdatedAt(Instant.now()); codOverrides.save(cod);
        GiftCard card = new GiftCard(); card.setCodeHash("hash-" + phno); card.setAmount(50); card.setRedeemedBy(phno);
        card.setRedeemedAt(Instant.now()); card.setCreatedAt(Instant.now()); giftCards.save(card);
        when(productClient.anonymiseReviews(VALID_KEY, phno)).thenReturn(3);

        mockMvc.perform(get("/customer/account/delete/preview").header("X-Customer-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.loyaltyPoints").value(40))
                .andExpect(jsonPath("$.storeCredit").value(25.5))
                .andExpect(jsonPath("$.orders").value(2));
        requestCode(token);
        String code = emailedCode();

        // Points and store credit are lost: the customer has to say they accept that.
        confirm(token, code, false).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("40 loyalty point")));
        assertTrue(accounts.findById(phno).isPresent());
        verify(productClient, never()).anonymiseReviews(anyString(), anyLong());

        confirm(token, code, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersAnonymised").value(2))
                .andExpect(jsonPath("$.addressesErased").value(1))
                .andExpect(jsonPath("$.wishlistItems").value(1))
                .andExpect(jsonPath("$.stockAlerts").value(1))
                .andExpect(jsonPath("$.supportTickets").value(1))
                .andExpect(jsonPath("$.reviewsAnonymised").value(3))
                .andExpect(jsonPath("$.loyaltyPointsForfeited").value(40))
                .andExpect(jsonPath("$.storeCreditForfeited").value(25.5));

        // Sign-in is gone: email unbound, sessions and codes dropped, and the old token no longer works.
        assertTrue(accounts.findById(phno).isEmpty());
        assertTrue(sessions.findAll().stream().noneMatch(s -> s.getPhno() == phno));
        assertTrue(deletionCodes.findByPhno(phno).isEmpty());
        mockMvc.perform(get("/customer/session").header("X-Customer-Token", token)).andExpect(status().isUnauthorized());

        // Personal data is erased...
        assertTrue(wishlist.findByCustomerPhno(phno).isEmpty());
        assertTrue(wishlistShares.findByCustomerPhno(phno).isEmpty());
        assertTrue(waitlist.findByCustomerPhno(phno).isEmpty());
        assertTrue(savedCarts.findById(phno).isEmpty());
        assertTrue(loyalty.findById(phno).isEmpty());
        assertTrue(loyaltyTransactions.findByCustomerPhnoOrderByTimestampDesc(phno).isEmpty());
        assertTrue(credit.findById(phno).isEmpty());
        assertTrue(creditTransactions.findTop50ByPhnoOrderByIdDesc(phno).isEmpty());
        assertTrue(subscriptions.findByCustomerPhnoOrderByIdDesc(phno).isEmpty());
        assertTrue(tickets.findByCustomerPhnoOrderByIdDesc(phno).isEmpty());
        assertTrue(messages.findByTicketIdOrderByIdAsc(ticket.getId()).isEmpty());
        assertTrue(referrals.findByRefereePhno(phno).isEmpty());
        assertTrue(couponRedemptions.findByCouponCodeAndCustomerPhno("WELCOME", phno).isEmpty());
        assertTrue(codOverrides.findById(phno).isEmpty());
        assertTrue(addresses.findByCustomerPhno(phno).isEmpty());
        assertTrue(addresses.findById(unused.getId()).isEmpty());

        // ...orders are kept without a name or phone number, and so is the state the goods went to (GST place of supply).
        assertEquals(0, carts.findBycustomerPhno(phno).size());
        Cart keptOrder = carts.findById(delivered.getOrderId()).orElseThrow();
        assertEquals("Deleted customer", keptOrder.getCustomerName());
        assertEquals(0L, keptOrder.getCustomerPhno());
        assertNull(keptOrder.getUpiId());
        assertNull(keptOrder.getDeliveryNote());
        assertNull(keptOrder.getCancelNote());
        assertEquals(100.0, keptOrder.getTotalPrice());
        assertEquals(OrderStatus.DELIVERED, keptOrder.getStatus());
        assertEquals("Deleted customer", carts.findById(cancelled.getOrderId()).orElseThrow().getCustomerName());
        ShippingAddress keptAddress = addresses.findById(shippedTo.getId()).orElseThrow();
        assertEquals("Karnataka", keptAddress.getState());
        assertEquals(0L, keptAddress.getCustomerPhno());
        assertEquals("Address erased", keptAddress.getLine1());
        assertNull(keptAddress.getLine2());
        assertNull(keptAddress.getCity());
        assertNull(keptAddress.getPincode());
        assertNull(keptAddress.getLabel());
        assertFalse(keptAddress.isDefault());
        ProductQuestion keptQuestion = questions.findById(q.getId()).orElseThrow();
        assertEquals(0L, keptQuestion.getAskerPhno());
        assertEquals("Is it waterproof?", keptQuestion.getQuestion());
        OrderFeedback keptFeedback = feedback.findById(fb.getId()).orElseThrow();
        assertEquals(0L, keptFeedback.getCustomerPhno());
        assertEquals(5, keptFeedback.getRating());
        assertNull(keptFeedback.getComment());
        assertEquals(0L, giftCards.findById(card.getId()).orElseThrow().getRedeemedBy());

        // ...and nobody else was touched.
        assertTrue(accounts.findById(other).isPresent());
        assertEquals(1, wishlist.findByCustomerPhno(other).size());
        assertEquals("Asha Rao", carts.findById(othersOrder.getOrderId()).orElseThrow().getCustomerName());
        assertEquals(other, carts.findById(othersOrder.getOrderId()).orElseThrow().getCustomerPhno());
        assertEquals("5 Other Road", addresses.findById(othersAddress.getId()).orElseThrow().getLine1());

        // The customer is told it happened, at the address that was bound.
        verify(mailService).send(eq("asha@example.com"), eq("Your account has been deleted"), anyString());
    }

    @Test
    void thePhoneNumberCanSignUpAgainAsAFreshAccountAfterDeletion() throws Exception {
        long phno = 9100000004L;
        String token = signedIn(phno, "first@example.com");
        when(productClient.anonymiseReviews(VALID_KEY, phno)).thenReturn(0);
        requestCode(token);
        confirm(token, emailedCode(), false).andExpect(status().isOk());

        // A different email can now claim the number - it is no longer bound to the old one.
        customerAuthService.requestCode(phno, "second@example.com");
        verify(mailService).send(eq("second@example.com"), eq("Your sign-in code"), anyString());
        assertTrue(carts.findBycustomerPhno(phno).isEmpty());
    }

    // ---------- what blocks it ----------

    @Test
    void anOrderStillInProgressBlocksDeletionBeforeAnyCodeIsSent() throws Exception {
        long phno = 9100000005L;
        String token = signedIn(phno, "busy@example.com");
        order(phno, OrderStatus.SHIPPED, PaymentMethod.PHONEPE, true, null);

        mockMvc.perform(get("/customer/account/delete/preview").header("X-Customer-Token", token))
                .andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("still in progress")));
        mockMvc.perform(post("/customer/account/delete/request").header("X-Customer-Token", token))
                .andExpect(status().isConflict());
        verify(mailService, never()).send(eq("busy@example.com"), eq("Confirm deleting your account"), anyString());
        assertTrue(accounts.findById(phno).isPresent());
    }

    @Test
    void aDeliveredCashOrderNotMarkedPaidBlocksDeletionButAPaidOneDoesNot() throws Exception {
        long phno = 9100000006L;
        String token = signedIn(phno, "cash@example.com");
        Cart unpaid = order(phno, OrderStatus.DELIVERED, PaymentMethod.CASH, false, null);

        mockMvc.perform(get("/customer/account/delete/preview").header("X-Customer-Token", token))
                .andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("cash-on-delivery")));

        unpaid.setPaid(true);
        carts.save(unpaid);
        mockMvc.perform(get("/customer/account/delete/preview").header("X-Customer-Token", token))
                .andExpect(jsonPath("$.canDelete").value(true));
    }

    @Test
    void anOrderPlacedAfterTheCodeWasSentStillBlocksTheConfirmation() throws Exception {
        long phno = 9100000007L;
        String token = signedIn(phno, "late@example.com");
        requestCode(token);
        String code = emailedCode();
        order(phno, OrderStatus.PLACED, PaymentMethod.PHONEPE, true, null);

        confirm(token, code, true).andExpect(status().isConflict());
        assertTrue(accounts.findById(phno).isPresent());
        verify(productClient, never()).anonymiseReviews(anyString(), anyLong());
    }

    // ---------- the code ----------

    @Test
    void aWrongCodeIsRefusedAndTooManyWrongGuessesBurnTheCode() throws Exception {
        long phno = 9100000008L;
        String token = signedIn(phno, "guess@example.com");
        requestCode(token);
        String right = emailedCode();
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            confirm(token, wrong, true).andExpect(status().isUnauthorized());
        }
        // Even the right code no longer works: ask for a new one.
        confirm(token, right, true).andExpect(status().isUnauthorized());
        assertTrue(deletionCodes.findByPhno(phno).isEmpty());
        assertTrue(accounts.findById(phno).isPresent());
        verify(productClient, never()).anonymiseReviews(anyString(), anyLong());
    }

    @Test
    void confirmingWithoutEverRequestingACodeIsRefused() throws Exception {
        long phno = 9100000009L;
        String token = signedIn(phno, "nocode@example.com");

        confirm(token, "123456", true).andExpect(status().isUnauthorized());
        assertTrue(accounts.findById(phno).isPresent());
    }

    @Test
    void aSignInCodeCannotConfirmADeletion() throws Exception {
        long phno = 9100000010L;
        String token = signedIn(phno, "mixup@example.com");
        // A sign-in code exists for this phone, but no deletion code was ever requested.
        customerAuthService.requestCode(phno, "mixup@example.com");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("mixup@example.com"), eq("Your sign-in code"), body.capture());
        Matcher m = Pattern.compile("code is (\\d{6})").matcher(body.getValue());
        assertTrue(m.find());

        confirm(token, m.group(1), true).andExpect(status().isUnauthorized());
        assertTrue(accounts.findById(phno).isPresent());
    }

    @Test
    void askingForAnotherCodeStraightAwayIsRefused() throws Exception {
        long phno = 9100000011L;
        String token = signedIn(phno, "twice@example.com");
        requestCode(token);

        mockMvc.perform(post("/customer/account/delete/request").header("X-Customer-Token", token))
                .andExpect(status().isTooManyRequests());
    }

    // ---------- when ProductService is down ----------

    @Test
    void ifProductServiceCannotBeReachedNothingIsErasedAndTheCodeStillWorksOnRetry() throws Exception {
        long phno = 9100000012L;
        String token = signedIn(phno, "down@example.com");
        Wishlist w = new Wishlist(); w.setCustomerPhno(phno); w.setProductId(3); wishlist.save(w);
        Cart done = order(phno, OrderStatus.DELIVERED, PaymentMethod.PHONEPE, true, null);
        requestCode(token);
        String code = emailedCode();
        when(productClient.anonymiseReviews(VALID_KEY, phno)).thenThrow(new RuntimeException("connection refused"));

        confirm(token, code, true).andExpect(status().isBadGateway());

        assertTrue(accounts.findById(phno).isPresent());
        assertEquals(1, wishlist.findByCustomerPhno(phno).size());
        assertEquals("Asha Rao", carts.findById(done.getOrderId()).orElseThrow().getCustomerName());

        // doReturn: re-stubbing with when(...) would call the mock again, and it is set to throw.
        org.mockito.Mockito.doReturn(0).when(productClient).anonymiseReviews(VALID_KEY, phno);
        confirm(token, code, true).andExpect(status().isOk());
        assertTrue(accounts.findById(phno).isEmpty());
        assertTrue(wishlist.findByCustomerPhno(phno).isEmpty());
    }
}
