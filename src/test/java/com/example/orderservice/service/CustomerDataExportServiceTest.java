package com.example.orderservice.service;

import com.example.orderservice.dto.CustomerDataExport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.Referral;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.LoyaltyTransactionRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.OrderFeedbackRepository;
import com.example.orderservice.repository.ProductQuestionRepository;
import com.example.orderservice.repository.ReferralRepository;
import com.example.orderservice.repository.SavedCartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.WishlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerDataExportServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private CartRepository orders;
    @Mock
    private ShippingAddressRepository addresses;
    @Mock
    private WishlistRepository wishlist;
    @Mock
    private StockWaitlistRepository waitlist;
    @Mock
    private LoyaltyAccountRepository loyalty;
    @Mock
    private LoyaltyTransactionRepository loyaltyTransactions;
    @Mock
    private ProductQuestionRepository questions;
    @Mock
    private OrderFeedbackRepository feedback;
    @Mock
    private SavedCartRepository savedCarts;
    @Mock
    private NotificationLogRepository notifications;
    @Mock
    private ReferralRepository referrals;

    private CustomerDataExportService service;

    @BeforeEach
    void setUp() {
        service = new CustomerDataExportService(accounts, orders, addresses, wishlist, waitlist, loyalty,
                loyaltyTransactions, questions, feedback, savedCarts, notifications, referrals,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Cart order(long id) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(PHNO);
        return c;
    }

    @Test
    void exportGathersEverythingForThePhone() {
        CustomerAccount account = new CustomerAccount();
        account.setPhno(PHNO);
        account.setEmail("a@example.com");
        account.setReferralCode("ABCD2345");
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account));
        when(orders.findBycustomerPhno(PHNO)).thenReturn(List.of(order(1), order(2)));
        when(notifications.findByOrderIdInOrderBySentAtDesc(List.of(1L, 2L))).thenReturn(List.of());
        when(referrals.findByReferrerPhno(PHNO)).thenReturn(List.of(new Referral(), new Referral()));
        when(referrals.findByRefereePhno(PHNO)).thenReturn(Optional.of(new Referral()));

        CustomerDataExport export = service.export(PHNO);

        assertEquals(NOW, export.exportedAt());
        assertEquals("a@example.com", export.account().email());
        assertEquals(2, export.orders().size());
        assertEquals(2, export.referrals().friendsReferred());
        assertTrue(export.referrals().usedAFriendsCode());
    }

    @Test
    void aPhoneWithNothingStoredGivesEmptySectionsAndSkipsTheNotificationQuery() {
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        CustomerDataExport export = service.export(PHNO);

        assertNull(export.account());
        assertNull(export.loyalty());
        assertNull(export.savedCart());
        assertTrue(export.orders().isEmpty());
        assertTrue(export.notifications().isEmpty());
        assertFalse(export.referrals().usedAFriendsCode());
        verify(notifications, never()).findByOrderIdInOrderBySentAtDesc(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void invalidPhoneIsRejected() {
        assertThrows(ProductException.class, () -> service.export(123L));
    }
}
