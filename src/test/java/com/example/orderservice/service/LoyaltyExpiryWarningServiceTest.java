package com.example.orderservice.service;

import com.example.orderservice.dto.LoyaltyExpiryWarningResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoyaltyExpiryWarningServiceTest {

    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private LoyaltyAccountRepository accounts;
    @Mock
    private CustomerAccountRepository customers;
    @Mock
    private MailService mailService;

    private LoyaltyExpiryWarningService service;

    @BeforeEach
    void setUp() {
        service = new LoyaltyExpiryWarningService(accounts, customers, mailService, Clock.fixed(NOW, ZoneOffset.UTC), 14, "UTC");
    }

    // Expires in 10 days: last activity was 355 days ago.
    private LoyaltyAccount expiringIn10Days(int points) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setCustomerPhno(PHNO);
        account.setPointsBalance(points);
        account.setLastActivityAt(NOW.minus(355, ChronoUnit.DAYS));
        return account;
    }

    private void verifiedEmail() {
        CustomerAccount customer = new CustomerAccount();
        customer.setPhno(PHNO);
        customer.setEmail("asha@example.com");
        when(customers.findById(PHNO)).thenReturn(Optional.of(customer));
    }

    @Test
    void looksOnlyAtAccountsWhoseLastActivityPutsExpiryInsideTheWarningWindow() {
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of());

        service.run();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(accounts).findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), from.capture(), to.capture());
        assertEquals(NOW.minus(365, ChronoUnit.DAYS), from.getValue());   // already expiring right now
        assertEquals(NOW.minus(351, ChronoUnit.DAYS), to.getValue());     // expiring in 14 days
    }

    @Test
    void emailsTheBalanceAndTheExpiryDateAndRemembersTheWarning() {
        LoyaltyAccount account = expiringIn10Days(480);
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of(account));
        verifiedEmail();
        when(mailService.send(eq("asha@example.com"), eq("480 loyalty points expire on 17 Oct 2026"), anyString())).thenReturn(true);

        LoyaltyExpiryWarningResult result = service.run();

        assertEquals(1, result.emailsSent());
        assertEquals(NOW, account.getExpiryWarnedAt());
        verify(accounts).save(account);
    }

    @Test
    void doesNotWarnTwiceForTheSameExpiry() {
        LoyaltyAccount account = expiringIn10Days(480);
        account.setExpiryWarnedAt(NOW.minus(1, ChronoUnit.DAYS));
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of(account));

        assertEquals(0, service.run().emailsSent());
        verifyNoInteractions(mailService);
        verify(accounts, never()).save(any());
    }

    // A warning from an earlier year must not suppress this year's: activity since then moved lastActivityAt past it.
    @Test
    void anOldWarningDoesNotSuppressANewExpiryAfterLaterActivity() {
        LoyaltyAccount account = expiringIn10Days(480);
        account.setExpiryWarnedAt(NOW.minus(400, ChronoUnit.DAYS));
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of(account));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        assertEquals(1, service.run().emailsSent());
    }

    @Test
    void aCustomerWithoutAVerifiedEmailIsCountedAndLeftUnwarned() {
        LoyaltyAccount account = expiringIn10Days(480);
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of(account));
        when(customers.findById(PHNO)).thenReturn(Optional.empty());

        LoyaltyExpiryWarningResult result = service.run();

        assertEquals(1, result.customersWithoutEmail());
        assertNull(account.getExpiryWarnedAt());
        verifyNoInteractions(mailService);
    }

    @Test
    void aFailedSendIsRetriedNextRunBecauseNothingWasRecorded() {
        LoyaltyAccount account = expiringIn10Days(480);
        when(accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(eq(0), any(), any())).thenReturn(List.of(account));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        assertEquals(1, service.run().sendFailures());
        assertNull(account.getExpiryWarnedAt());
        verify(accounts, never()).save(any());
    }

    @Test
    void theExpiryDateIsDerivedFromLastActivityAndOnlyWhilePointsRemain() {
        LoyaltyAccount account = expiringIn10Days(480);
        assertEquals(NOW.plus(10, ChronoUnit.DAYS), account.getPointsExpireAt());

        account.setPointsBalance(0);
        assertNull(account.getPointsExpireAt());
    }
}
