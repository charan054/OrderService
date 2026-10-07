package com.example.orderservice.service;

import com.example.orderservice.dto.EmailPreferences;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailPreferenceServiceTest {
    private static final long PHNO = 9876543210L;

    @Mock
    private CustomerAccountRepository accounts;

    private EmailPreferenceService service;
    private CustomerAccount account;

    @BeforeEach
    void setUp() {
        service = new EmailPreferenceService(accounts, "secret-key", "http://shop.example/");
        account = new CustomerAccount();
        account.setPhno(PHNO);
        account.setEmail("asha@example.com");
    }

    private void accountExists() {
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account));
    }

    @Test
    void customersReceivePromotionalEmailUntilTheyOptOut() {
        accountExists();

        assertTrue(service.get(PHNO).marketingEmails());
    }

    @Test
    void theSwitchIsPersistedBothWays() {
        accountExists();

        assertEquals(new EmailPreferences(false), service.set(PHNO, false));
        assertTrue(account.isMarketingOptOut());
        assertEquals(new EmailPreferences(true), service.set(PHNO, true));
        assertFalse(account.isMarketingOptOut());
        verify(accounts, org.mockito.Mockito.times(2)).save(account);
    }

    @Test
    void aValidLinkTokenUnsubscribes() {
        accountExists();

        service.unsubscribe(PHNO, service.token(PHNO));

        assertTrue(account.isMarketingOptOut());
    }

    @Test
    void aTokenForAnotherNumberOrAGarbageTokenIsRejected() {
        assertThrows(ResponseStatusException.class, () -> service.unsubscribe(PHNO, service.token(9000000001L)));
        assertThrows(ResponseStatusException.class, () -> service.unsubscribe(PHNO, "nonsense"));
        assertThrows(ResponseStatusException.class, () -> service.unsubscribe(PHNO, null));
        verify(accounts, never()).save(any());
    }

    @Test
    void aTokenFromADifferentSecretIsRejected() {
        EmailPreferenceService other = new EmailPreferenceService(accounts, "another-secret", "");

        assertThrows(ResponseStatusException.class, () -> service.unsubscribe(PHNO, other.token(PHNO)));
    }

    @Test
    void anUnknownNumberIsNotFound() {
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.get(PHNO));
    }

    @Test
    void theFooterLinksToTheUnsubscribeEndpointWithoutADoubleSlash() {
        String footer = service.footer(PHNO);

        assertTrue(footer.contains("http://shop.example/prefs/unsubscribe?phno=" + PHNO + "&token=" + service.token(PHNO)));
    }

    @Test
    void withoutABaseUrlTheFooterPointsToMyAccount() {
        String footer = new EmailPreferenceService(accounts, "k", "").footer(PHNO);

        assertFalse(footer.contains("http"));
        assertTrue(footer.contains("My account"));
    }
}
