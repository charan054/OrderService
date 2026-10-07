package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AbandonedCartResult;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.SavedCartRepository;
import feign.FeignException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbandonedCartServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final long PHNO = 9876543210L;

    @Mock
    private SavedCartRepository carts;
    @Mock
    private CustomerAccountRepository customers;
    @Mock
    private ProductClient productClient;
    @Mock
    private MailService mailService;

    private final EmailPreferenceService preferences =
            new EmailPreferenceService(org.mockito.Mockito.mock(CustomerAccountRepository.class), "k", "http://shop.example");

    private AbandonedCartService service;

    @BeforeEach
    void setUp() {
        service = new AbandonedCartService(carts, customers, productClient, mailService, preferences,
                Clock.fixed(NOW, ZoneOffset.UTC), 24, 7);
    }

    private SavedCart cart(int... productIdsAndQuantities) {
        SavedCart c = new SavedCart();
        c.setPhno(PHNO);
        c.setUpdatedAt(NOW.minusSeconds(3600L * 30));
        for (int i = 0; i < productIdsAndQuantities.length; i += 2) {
            c.getLines().add(new SavedCart.Line(productIdsAndQuantities[i], productIdsAndQuantities[i + 1]));
        }
        return c;
    }

    private void candidates(SavedCart... list) {
        when(carts.findByUpdatedAtBetweenAndReminderSentAtIsNull(NOW.minusSeconds(7 * 86400L), NOW.minusSeconds(24 * 3600L)))
                .thenReturn(List.of(list));
    }

    private Product product(String name) {
        Product p = new Product();
        p.setProductName(name);
        return p;
    }

    private void verifiedEmail() {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(PHNO);
        a.setEmail("a@example.com");
        when(customers.findById(PHNO)).thenReturn(Optional.of(a));
    }

    @Test
    void emailsTheItemsAndMarksTheCartReminded() {
        SavedCart c = cart(1, 2, 2, 1);
        candidates(c);
        verifiedEmail();
        when(productClient.getProductById(1)).thenReturn(product("Soap"));
        when(productClient.getProductById(2)).thenReturn(product("Shampoo"));
        when(mailService.send(eq("a@example.com"), any(), any())).thenReturn(true);

        AbandonedCartResult r = service.run();

        assertEquals(1, r.remindersSent());
        assertEquals(NOW, c.getReminderSentAt());
        verify(carts).save(c);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("a@example.com"), any(), body.capture());
        assertTrue(body.getValue().contains("2 x Soap"));
        assertTrue(body.getValue().contains("1 x Shampoo"));
        assertTrue(body.getValue().contains("http://shop.example/prefs/unsubscribe?phno=" + PHNO + "&token="));
    }

    @Test
    void aCustomerWhoUnsubscribedIsNotEmailedAndTheCartIsLeftAlone() {
        SavedCart c = cart(1, 1);
        candidates(c);
        CustomerAccount a = new CustomerAccount();
        a.setPhno(PHNO);
        a.setEmail("a@example.com");
        a.setMarketingOptOut(true);
        when(customers.findById(PHNO)).thenReturn(Optional.of(a));

        AbandonedCartResult r = service.run();

        assertEquals(1, r.optedOut());
        assertEquals(0, r.remindersSent());
        assertNull(c.getReminderSentAt());
        verify(mailService, never()).send(any(), any(), any());
    }

    @Test
    void aFailedSendLeavesTheCartForTheNextRun() {
        SavedCart c = cart(1, 1);
        candidates(c);
        verifiedEmail();
        when(productClient.getProductById(1)).thenReturn(product("Soap"));
        when(mailService.send(any(), any(), any())).thenReturn(false);

        AbandonedCartResult r = service.run();

        assertEquals(1, r.sendFailures());
        assertNull(c.getReminderSentAt());
        verify(carts, never()).save(any());
    }

    @Test
    void aCustomerWithoutAVerifiedEmailIsCountedNotEmailed() {
        candidates(cart(1, 1));
        when(customers.findById(PHNO)).thenReturn(Optional.empty());

        AbandonedCartResult r = service.run();

        assertEquals(1, r.cartsWithoutEmail());
        verify(mailService, never()).send(any(), any(), any());
    }

    @Test
    void productsGoneFromTheCatalogAreLeftOutAndAnAllGoneCartIsSkipped() {
        SavedCart partly = cart(1, 1, 2, 1);
        SavedCart allGone = cart(3, 1);
        candidates(partly, allGone);
        verifiedEmail();
        when(productClient.getProductById(1)).thenReturn(product("Soap"));
        when(productClient.getProductById(2)).thenThrow(org.mockito.Mockito.mock(FeignException.class));
        when(productClient.getProductById(3)).thenReturn(null);
        when(mailService.send(any(), any(), any())).thenReturn(true);

        AbandonedCartResult r = service.run();

        assertEquals(1, r.remindersSent());
        assertEquals(1, r.cartsSkipped());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(any(), any(), body.capture());
        assertTrue(body.getValue().contains("1 x Soap"));
        assertEquals(false, body.getValue().contains("Item"));
    }

    @Test
    void nothingToDoWhenNoCartQualifies() {
        candidates();

        AbandonedCartResult r = service.run();

        assertEquals(0, r.remindersSent());
        verify(mailService, never()).send(any(), any(), any());
    }
}
