package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.PriceMatchRequest;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.PriceMatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceMatchServiceTest {
    private static final long CUSTOMER = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String URL = "https://shop.example/item/1";

    @Mock
    private PriceMatchRequestRepository requests;
    @Mock
    private ProductClient productClient;
    @Mock
    private CustomerNotifier notifier;

    private PriceMatchService service;

    @BeforeEach
    void setUp() {
        service = new PriceMatchService(requests, productClient, Clock.fixed(NOW, ZoneOffset.UTC), notifier);
    }

    private void productAt(double price) {
        Product p = new Product();
        p.setProductPrice(price);
        when(productClient.getProductById(1)).thenReturn(p);
    }

    private PriceMatchRequest open(long id) {
        PriceMatchRequest r = new PriceMatchRequest();
        r.setId(id);
        r.setStatus(PriceMatchRequest.Status.OPEN);
        return r;
    }

    @Test
    void requestRecordsBothPricesAndStartsOpen() {
        productAt(100);
        when(requests.save(any(PriceMatchRequest.class))).thenAnswer(i -> i.getArgument(0));

        PriceMatchRequest r = service.request(CUSTOMER, 1, 80, "  " + URL + " ");

        assertEquals(PriceMatchRequest.Status.OPEN, r.getStatus());
        assertEquals(80, r.getCompetitorPrice());
        assertEquals(100, r.getOurPrice());
        assertEquals(URL, r.getCompetitorUrl());
        assertEquals(NOW, r.getCreatedAt());
    }

    @Test
    void aPriceNotBelowOursIsRejected() {
        productAt(100);
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 100, URL));
        verify(requests, never()).save(any());
    }

    @Test
    void badPricesAndLinksAreRejectedBeforeAnyLookup() {
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 0, URL));
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, Double.NaN, URL));
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, ""));
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, "javascript:alert(1)"));
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, "ftp://x.example/a"));
        assertThrows(ProductException.class, () -> service.request(12345L, 1, 50, URL));
        verify(productClient, never()).getProductById(1);
    }

    @Test
    void anUnknownProductIsRejected() {
        when(productClient.getProductById(1)).thenReturn(null);
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, URL));
    }

    @Test
    void onlyOneOpenRequestPerProduct() {
        productAt(100);
        when(requests.existsByCustomerPhnoAndProductIdAndStatus(CUSTOMER, 1, PriceMatchRequest.Status.OPEN)).thenReturn(true);
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, URL));
        verify(requests, never()).save(any());
    }

    @Test
    void aCustomerCannotFloodTheQueue() {
        productAt(100);
        when(requests.countByCustomerPhnoAndStatus(CUSTOMER, PriceMatchRequest.Status.OPEN))
                .thenReturn((long) PriceMatchService.MAX_OPEN_PER_CUSTOMER);
        assertThrows(ProductException.class, () -> service.request(CUSTOMER, 1, 50, URL));
    }

    @Test
    void approvingStampsTheDecisionAndKeepsTheNote() {
        PriceMatchRequest r = open(7);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        when(requests.save(r)).thenReturn(r);

        PriceMatchRequest out = service.resolve(7, true, "  Sale starts tomorrow ");

        assertEquals(PriceMatchRequest.Status.APPROVED, out.getStatus());
        assertEquals("Sale starts tomorrow", out.getAdminNote());
        assertEquals(NOW, out.getResolvedAt());
    }

    @Test
    void resolvingEmailsTheCustomerWithTheDecisionAndNote() {
        PriceMatchRequest r = open(7);
        r.setCustomerPhno(CUSTOMER);
        r.setProductId(1);
        r.setCompetitorPrice(80);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        when(requests.save(r)).thenReturn(r);
        Product named = new Product();
        named.setProductName("Soap");
        when(productClient.getProductById(1)).thenReturn(named);

        service.resolve(7, false, "Different size");

        verify(notifier).notifyPriceMatchDecision(CUSTOMER, "Soap", 80, false, "Different size");
    }

    @Test
    void aCatalogOutageDoesNotBlockTheDecisionOrItsEmail() {
        PriceMatchRequest r = open(7);
        r.setCustomerPhno(CUSTOMER);
        r.setProductId(1);
        r.setCompetitorPrice(80);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        when(requests.save(r)).thenReturn(r);
        when(productClient.getProductById(1)).thenThrow(new RuntimeException("down"));

        service.resolve(7, true, null);

        verify(notifier).notifyPriceMatchDecision(CUSTOMER, "product #1", 80, true, null);
    }

    @Test
    void decliningWithNoNoteLeavesItNull() {
        PriceMatchRequest r = open(7);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        when(requests.save(r)).thenReturn(r);

        PriceMatchRequest out = service.resolve(7, false, " ");

        assertEquals(PriceMatchRequest.Status.DECLINED, out.getStatus());
        assertNull(out.getAdminNote());
    }

    @Test
    void aResolvedRequestCannotBeResolvedAgain() {
        PriceMatchRequest r = open(7);
        r.setStatus(PriceMatchRequest.Status.DECLINED);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        assertThrows(ProductException.class, () -> service.resolve(7, true, null));
        verify(requests, never()).save(any());
    }

    @Test
    void resolvingAnUnknownRequestFails() {
        when(requests.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.resolve(9, true, null));
    }
}
