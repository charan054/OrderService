package com.example.orderservice.service;

import com.example.orderservice.dto.ReferralInfo;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.Referral;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.ReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    private static final long REFERRER = 9000000001L;
    private static final long FRIEND = 9000000002L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private CustomerAccountRepository customers;
    @Mock
    private ReferralRepository referrals;
    @Mock
    private CartRepository orders;
    @Mock
    private OrderService orderService;
    @Mock
    private MailService mailService;

    private ReferralService service;

    @BeforeEach
    void setUp() {
        service = new ReferralService(customers, referrals, orders, orderService, mailService,
                Clock.fixed(NOW, ZoneOffset.UTC), 100, 200.0, 2);
    }

    private CustomerAccount account(long phno, String email, String code) {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(phno);
        a.setEmail(email);
        a.setReferralCode(code);
        return a;
    }

    private Referral pendingReferral() {
        Referral r = new Referral();
        r.setId(5L);
        r.setReferrerPhno(REFERRER);
        r.setRefereePhno(FRIEND);
        return r;
    }

    private Cart deliveredOrder(double total, double refunded) {
        Cart c = new Cart();
        c.setOrderId(77L);
        c.setCustomerPhno(FRIEND);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        return c;
    }

    // ---------- the customer's own card ----------

    @Test
    void theFirstLookCreatesAReadableUniqueCodeAndLaterLooksKeepIt() {
        CustomerAccount me = account(REFERRER, "a@example.com", null);
        when(customers.findById(REFERRER)).thenReturn(Optional.of(me));
        when(customers.findByReferralCode(anyString())).thenReturn(Optional.empty());
        when(referrals.findByReferrerPhno(REFERRER)).thenReturn(List.of());
        when(referrals.findByRefereePhno(REFERRER)).thenReturn(Optional.empty());

        ReferralInfo first = service.getInfo(REFERRER);

        assertNotNull(first.code());
        assertEquals(8, first.code().length());
        assertTrue(first.code().matches("[A-HJKMNP-Z2-9]{8}"), first.code());
        verify(customers).save(me);

        ReferralInfo second = service.getInfo(REFERRER);
        assertEquals(first.code(), second.code());
    }

    @Test
    void theCardCountsFriendsAndPointsEarned() {
        when(customers.findById(REFERRER)).thenReturn(Optional.of(account(REFERRER, "a@example.com", "ABCD2345")));
        Referral rewarded = pendingReferral();
        rewarded.setRewarded(true);
        rewarded.setBonusPoints(100);
        when(referrals.findByReferrerPhno(REFERRER)).thenReturn(List.of(rewarded, pendingReferral()));
        when(referrals.findByRefereePhno(REFERRER)).thenReturn(Optional.empty());

        ReferralInfo info = service.getInfo(REFERRER);

        assertEquals(2, info.friendsReferred());
        assertEquals(1, info.friendsRewarded());
        assertEquals(100, info.pointsEarned());
        assertFalse(info.usedAFriendsCode());
    }

    @Test
    void aNumberWithoutAVerifiedAccountCannotHaveACode() {
        when(customers.findById(REFERRER)).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.getInfo(REFERRER));
    }

    // ---------- applying a friend's code ----------

    private void friendReadyToApply() {
        when(customers.findById(FRIEND)).thenReturn(Optional.of(account(FRIEND, "f@example.com", null)));
        when(customers.findByReferralCode("ABCD2345")).thenReturn(Optional.of(account(REFERRER, "a@example.com", "ABCD2345")));
    }

    @Test
    void aNewCustomerCanApplyAFriendsCodeCaseInsensitively() {
        friendReadyToApply();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.empty());
        when(orders.findBycustomerPhno(FRIEND)).thenReturn(List.of());

        service.apply(FRIEND, "  abcd2345 ");

        ArgumentCaptor<Referral> saved = ArgumentCaptor.forClass(Referral.class);
        verify(referrals).save(saved.capture());
        assertEquals(REFERRER, saved.getValue().getReferrerPhno());
        assertEquals(FRIEND, saved.getValue().getRefereePhno());
        assertEquals(NOW, saved.getValue().getCreatedAt());
        assertFalse(saved.getValue().isRewarded());
    }

    @Test
    void unknownBlankOwnAndSameEmailCodesAreRejected() {
        assertThrows(ProductException.class, () -> service.apply(FRIEND, " "));

        when(customers.findById(FRIEND)).thenReturn(Optional.of(account(FRIEND, "f@example.com", "MINE2345")));
        when(customers.findByReferralCode("NOPE2345")).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.apply(FRIEND, "nope2345"));

        when(customers.findByReferralCode("MINE2345")).thenReturn(Optional.of(account(FRIEND, "f@example.com", "MINE2345")));
        assertThrows(ProductException.class, () -> service.apply(FRIEND, "MINE2345"));

        // Two phone numbers sharing one verified email are the same person.
        when(customers.findByReferralCode("OTHER234")).thenReturn(Optional.of(account(REFERRER, "F@Example.com", "OTHER234")));
        assertThrows(ProductException.class, () -> service.apply(FRIEND, "OTHER234"));
        verify(referrals, never()).save(any());
    }

    @Test
    void aCustomerWhoAlreadyUsedACodeOrAlreadyOrderedCannotApply() {
        friendReadyToApply();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(pendingReferral()));
        assertThrows(ProductException.class, () -> service.apply(FRIEND, "ABCD2345"));

        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.empty());
        when(orders.findBycustomerPhno(FRIEND)).thenReturn(List.of(new Cart()));
        assertThrows(ProductException.class, () -> service.apply(FRIEND, "ABCD2345"));
        verify(referrals, never()).save(any());
    }

    @Test
    void aReferrerAtTheirRewardLimitCannotTakeOnMoreFriends() {
        friendReadyToApply();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.empty());
        when(orders.findBycustomerPhno(FRIEND)).thenReturn(List.of());
        when(referrals.countByReferrerPhnoAndRewardedTrue(REFERRER)).thenReturn(2L);

        assertThrows(ProductException.class, () -> service.apply(FRIEND, "ABCD2345"));
    }

    // ---------- paying the bonus ----------

    @Test
    void aQualifyingDeliveryPaysBothSidesAndMarksTheReferralPaidFirst() {
        Referral referral = pendingReferral();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));
        when(referrals.countByReferrerPhnoAndRewardedTrue(REFERRER)).thenReturn(0L);
        when(customers.findById(anyLong())).thenReturn(Optional.empty());

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        assertTrue(referral.isRewarded());
        assertEquals(77L, referral.getRewardOrderId());
        assertEquals(100, referral.getBonusPoints());
        assertEquals(NOW, referral.getRewardedAt());
        verify(referrals).save(referral);
        verify(orderService).adjustLoyaltyPoints(eq(REFERRER), eq(100), contains("friend you referred"));
        verify(orderService).adjustLoyaltyPoints(eq(FRIEND), eq(100), contains("welcome bonus"));
    }

    @Test
    void anAlreadyRewardedReferralNeverPaysTwice() {
        Referral referral = pendingReferral();
        referral.setRewarded(true);
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        verifyNoInteractions(orderService);
        verify(referrals, never()).save(any());
    }

    @Test
    void anOrderBelowTheMinimumNetOfRefundsDoesNotQualifyYetButALaterOneCan() {
        Referral referral = pendingReferral();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(150, 0)));
        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 300))); // 150 net

        assertFalse(referral.isRewarded());
        verifyNoInteractions(orderService);
    }

    @Test
    void aCustomerWhoWasNeverReferredIsIgnored() {
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.empty());

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        verifyNoInteractions(orderService);
    }

    @Test
    void aReferrerThatHitTheLimitInTheMeantimeIsNotPaid() {
        Referral referral = pendingReferral();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));
        when(referrals.countByReferrerPhnoAndRewardedTrue(REFERRER)).thenReturn(2L);

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        assertFalse(referral.isRewarded());
        verifyNoInteractions(orderService);
    }

    // A failure while crediting must never break the delivery that triggered it, and never be retried into a double pay.
    @Test
    void aFailureCreditingPointsIsSwallowedAndTheReferralStaysMarkedPaid() {
        Referral referral = pendingReferral();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));
        when(referrals.countByReferrerPhnoAndRewardedTrue(REFERRER)).thenReturn(0L);
        when(orderService.adjustLoyaltyPoints(anyLong(), anyInt(), anyString())).thenThrow(new IllegalStateException("db down"));

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        assertTrue(referral.isRewarded());
    }

    @Test
    void bothSidesAreEmailedWhenTheyHaveAVerifiedAddress() {
        Referral referral = pendingReferral();
        when(referrals.findByRefereePhno(FRIEND)).thenReturn(Optional.of(referral));
        when(referrals.countByReferrerPhnoAndRewardedTrue(REFERRER)).thenReturn(0L);
        when(customers.findById(REFERRER)).thenReturn(Optional.of(account(REFERRER, "a@example.com", "ABCD2345")));
        when(customers.findById(FRIEND)).thenReturn(Optional.of(account(FRIEND, "f@example.com", null)));

        service.onOrderDelivered(new OrderDeliveredEvent(deliveredOrder(450, 0)));

        verify(mailService).send(eq("a@example.com"), eq("You earned 100 referral points"), anyString());
        verify(mailService).send(eq("f@example.com"), eq("Your 100 welcome points are here"), anyString());
    }
}
