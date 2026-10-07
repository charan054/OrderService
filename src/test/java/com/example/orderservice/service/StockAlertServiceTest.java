package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.StockAlertRunResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.StockWaitlistRepository;
import com.example.orderservice.repository.WishlistRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockAlertServiceTest {

    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private WishlistRepository wishlists;
    @Mock
    private StockWaitlistRepository waitlists;
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private ProductClient productClient;
    @Mock
    private MailService mailService;

    private final EmailPreferenceService preferences =
            new EmailPreferenceService(org.mockito.Mockito.mock(CustomerAccountRepository.class), "k", "http://shop.example");

    private StockAlertService service;

    @BeforeEach
    void setUp() {
        service = new StockAlertService(wishlists, waitlists, accounts, productClient, mailService, preferences,
                Clock.fixed(NOW, ZoneOffset.UTC), "http://shop.example/shop.html");
    }

    private Product product(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("Widget " + id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    private StockWaitlist waiting(int productId, Instant notifiedAt) {
        StockWaitlist w = new StockWaitlist();
        w.setCustomerPhno(PHNO);
        w.setProductId(productId);
        w.setNotifiedAt(notifiedAt);
        return w;
    }

    private Wishlist wished(int productId, Double priceWhenAdded, Double lastAlerted) {
        Wishlist w = new Wishlist();
        w.setCustomerPhno(PHNO);
        w.setProductId(productId);
        w.setPriceWhenAdded(priceWhenAdded);
        w.setLastAlertedPrice(lastAlerted);
        return w;
    }

    private void verifiedEmail() {
        CustomerAccount account = new CustomerAccount();
        account.setPhno(PHNO);
        account.setEmail("asha@example.com");
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account));
    }

    @Test
    void emailsAWaitlistedCustomerWhenTheProductIsBackInStockAndRecordsIt() {
        StockWaitlist entry = waiting(1, null);
        when(waitlists.findAll()).thenReturn(List.of(entry));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));
        verifiedEmail();
        when(mailService.send(eq("asha@example.com"), anyString(), contains("Back in stock: Widget 1"))).thenReturn(true);

        StockAlertRunResult result = service.run();

        assertEquals(1, result.emailsSent());
        assertEquals(1, result.restockAlerts());
        assertEquals(NOW, entry.getNotifiedAt());
        verify(waitlists).save(entry);
    }

    @Test
    void aCustomerWhoUnsubscribedGetsNoAlertAndNothingIsRecorded() {
        StockWaitlist entry = waiting(1, null);
        when(waitlists.findAll()).thenReturn(List.of(entry));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));
        CustomerAccount account = new CustomerAccount();
        account.setPhno(PHNO);
        account.setEmail("asha@example.com");
        account.setMarketingOptOut(true);
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account));

        StockAlertRunResult result = service.run();

        assertEquals(1, result.optedOut());
        assertEquals(0, result.emailsSent());
        assertNull(entry.getNotifiedAt());
        verifyNoInteractions(mailService);
    }

    @Test
    void doesNotEmailAgainForTheSameRestock() {
        when(waitlists.findAll()).thenReturn(List.of(waiting(1, NOW.minusSeconds(3600))));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));

        StockAlertRunResult result = service.run();

        assertEquals(0, result.emailsSent());
        verifyNoInteractions(mailService);
        verify(waitlists, never()).save(any());
    }

    @Test
    void aProductSeenSoldOutAgainArmsTheNextRestockEmail() {
        StockWaitlist entry = waiting(1, NOW.minusSeconds(3600));
        when(waitlists.findAll()).thenReturn(List.of(entry));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 0));

        service.run();

        assertNull(entry.getNotifiedAt());
        verify(waitlists).save(entry);
        verifyNoInteractions(mailService);
    }

    // No verified email: nothing is marked as sent, so the customer still gets it once they sign in and bind one.
    @Test
    void aCustomerWithoutAVerifiedEmailIsSkippedAndNothingIsRecorded() {
        StockWaitlist entry = waiting(1, null);
        when(waitlists.findAll()).thenReturn(List.of(entry));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        StockAlertRunResult result = service.run();

        assertEquals(1, result.customersWithoutEmail());
        assertEquals(0, result.emailsSent());
        assertNull(entry.getNotifiedAt());
        verify(waitlists, never()).save(any());
        verifyNoInteractions(mailService);
    }

    @Test
    void aFailedSendIsRetriedNextRunBecauseNothingWasRecorded() {
        StockWaitlist entry = waiting(1, null);
        when(waitlists.findAll()).thenReturn(List.of(entry));
        when(wishlists.findAll()).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        StockAlertRunResult result = service.run();

        assertEquals(1, result.sendFailures());
        assertEquals(0, result.emailsSent());
        assertNull(entry.getNotifiedAt());
        verify(waitlists, never()).save(any());
    }

    @Test
    void emailsAPriceDropOnceAndRemembersTheLowestAlertedPrice() {
        Wishlist item = wished(2, 100.0, null);
        when(waitlists.findAll()).thenReturn(List.of());
        when(wishlists.findAll()).thenReturn(List.of(item));
        when(productClient.getProductById(2)).thenReturn(product(2, 80.0, 5));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), contains("Widget 2 is now Rs. 80.00 (was Rs. 100.00)"))).thenReturn(true);

        StockAlertRunResult result = service.run();

        assertEquals(1, result.priceDropAlerts());
        assertEquals(80.0, item.getLastAlertedPrice());
        verify(wishlists).save(item);
    }

    @Test
    void doesNotRepeatAPriceDropAtTheSamePriceButAlertsAgainOnAFurtherDrop() {
        Wishlist alreadyAlerted = wished(2, 100.0, 80.0);
        when(waitlists.findAll()).thenReturn(List.of());
        when(wishlists.findAll()).thenReturn(List.of(alreadyAlerted));
        when(productClient.getProductById(2)).thenReturn(product(2, 80.0, 5));

        assertEquals(0, service.run().emailsSent());
        verifyNoInteractions(mailService);

        when(productClient.getProductById(2)).thenReturn(product(2, 70.0, 5));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        assertEquals(1, service.run().priceDropAlerts());
        assertEquals(70.0, alreadyAlerted.getLastAlertedPrice());
    }

    @Test
    void aPriceThatRecoversResetsTheMemorySoTheNextDropAlertsAgain() {
        Wishlist item = wished(2, 100.0, 80.0);
        when(waitlists.findAll()).thenReturn(List.of());
        when(wishlists.findAll()).thenReturn(List.of(item));
        when(productClient.getProductById(2)).thenReturn(product(2, 100.0, 5));

        service.run();

        assertNull(item.getLastAlertedPrice());
        verify(wishlists).save(item);
        verifyNoInteractions(mailService);
    }

    @Test
    void oneDigestEmailCarriesBothAnAlertTypesForTheSameCustomer() {
        when(waitlists.findAll()).thenReturn(List.of(waiting(1, null)));
        when(wishlists.findAll()).thenReturn(List.of(wished(2, 100.0, null)));
        when(productClient.getProductById(1)).thenReturn(product(1, 49.5, 12));
        when(productClient.getProductById(2)).thenReturn(product(2, 80.0, 5));
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        StockAlertRunResult result = service.run();

        assertEquals(1, result.emailsSent());
        assertEquals(1, result.restockAlerts());
        assertEquals(1, result.priceDropAlerts());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(1)).send(eq("asha@example.com"), anyString(), body.capture());
        assertTrue(body.getValue().contains("Back in stock: Widget 1"));
        assertTrue(body.getValue().contains("Price drop: Widget 2"));
        assertTrue(body.getValue().contains("http://shop.example/shop.html"));
    }

    @Test
    void aProductThatCannotBeLookedUpIsSkippedWithoutFailingTheRun() {
        when(waitlists.findAll()).thenReturn(List.of(waiting(9, null)));
        when(wishlists.findAll()).thenReturn(List.of(wished(9, 100.0, null)));
        when(productClient.getProductById(9)).thenThrow(mock(FeignException.class));

        StockAlertRunResult result = service.run();

        assertEquals(0, result.emailsSent());
        verifyNoInteractions(mailService);
    }

    @Test
    void aWishlistEntryFromBeforePricesWereSnapshottedIsSkipped() {
        when(waitlists.findAll()).thenReturn(List.of());
        when(wishlists.findAll()).thenReturn(List.of(wished(2, null, null)));

        assertEquals(0, service.run().emailsSent());
        verifyNoInteractions(productClient);
        assertNotNull(service);
    }
}
