package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.SharedWishlistItem;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.entity.WishlistShare;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.WishlistRepository;
import com.example.orderservice.repository.WishlistShareRepository;
import feign.FeignException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishlistShareServiceTest {
    private static final long PHNO = 9876543210L;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Mock
    private WishlistShareRepository shares;
    @Mock
    private WishlistRepository wishlist;
    @Mock
    private ProductClient productClient;

    private WishlistShareService service;

    @BeforeEach
    void setUp() {
        service = new WishlistShareService(shares, wishlist, productClient,
                Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC));
    }

    private Wishlist entry(int productId) {
        Wishlist w = new Wishlist();
        w.setCustomerPhno(PHNO);
        w.setProductId(productId);
        return w;
    }

    private Product product(int id, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("Soap " + id);
        p.setProductCategory("Care");
        p.setProductPrice(40);
        p.setProductStock(stock);
        return p;
    }

    private WishlistShare share() {
        WishlistShare s = new WishlistShare();
        s.setToken(TOKEN);
        s.setCustomerPhno(PHNO);
        return s;
    }

    @Test
    void mintsAnUnguessableTokenOnFirstShare() {
        when(shares.findByCustomerPhno(PHNO)).thenReturn(Optional.empty());
        when(shares.save(any(WishlistShare.class))).thenAnswer(i -> i.getArgument(0));

        String token = service.getOrCreateToken(PHNO);

        assertTrue(token.matches("[0-9a-f]{32}"));
        verify(shares).save(argThat(s -> s.getCustomerPhno() == PHNO && s.getCreatedAt() != null));
    }

    @Test
    void sharingAgainReturnsTheExistingLink() {
        when(shares.findByCustomerPhno(PHNO)).thenReturn(Optional.of(share()));

        assertEquals(TOKEN, service.getOrCreateToken(PHNO));
        verify(shares, never()).save(any());
    }

    @Test
    void revokeDeletesTheLink() {
        service.revoke(PHNO);
        verify(shares).deleteByCustomerPhno(PHNO);
    }

    @Test
    void rejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.getOrCreateToken(123L));
        assertThrows(ProductException.class, () -> service.revoke(123L));
    }

    @Test
    void viewListsLiveProductsAndSkipsOnesThatCannotBeLoaded() {
        when(shares.findById(TOKEN)).thenReturn(Optional.of(share()));
        when(wishlist.findByCustomerPhno(PHNO)).thenReturn(List.of(entry(1), entry(2), entry(3)));
        when(productClient.getProductById(1)).thenReturn(product(1, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 0));
        when(productClient.getProductById(3)).thenThrow(mock(FeignException.class));

        List<SharedWishlistItem> items = service.view(TOKEN);

        assertEquals(2, items.size());
        assertTrue(items.get(0).inStock());
        assertFalse(items.get(1).inStock());
        verify(shares).incrementViewCount(TOKEN);
    }

    @Test
    void anInvalidTokenIsNeverCounted() {
        when(shares.findById(TOKEN)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.view(TOKEN));
        assertThrows(OrderNotFoundException.class, () -> service.view("../etc"));
        verify(shares, never()).incrementViewCount(any());
    }

    @Test
    void findLinkReturnsTheOwnersShareWithItsViewCount() {
        WishlistShare s = share();
        s.setViewCount(7);
        when(shares.findByCustomerPhno(PHNO)).thenReturn(Optional.of(s));

        assertEquals(7, service.findLink(PHNO).orElseThrow().getViewCount());
        assertTrue(service.hasLink(PHNO));
    }

    @Test
    void anUnknownRevokedOrMalformedTokenIs404() {
        when(shares.findById(TOKEN)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.view(TOKEN));
        assertThrows(OrderNotFoundException.class, () -> service.view("not-a-token"));
        assertThrows(OrderNotFoundException.class, () -> service.view(null));
        verify(shares, never()).findById("not-a-token");
    }
}
