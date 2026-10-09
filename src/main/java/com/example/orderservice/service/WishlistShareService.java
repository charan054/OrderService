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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Lets a customer hand out a read-only link to their wishlist. Names, prices and stock are looked up live on every
 * view (same as the price-drop alerts), so a shared list is never stale.
 */
@Service
public class WishlistShareService {
    private static final int TOKEN_BYTES = 16;

    private final WishlistShareRepository shares;
    private final WishlistRepository wishlist;
    private final ProductClient productClient;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WishlistShareService(WishlistShareRepository shares, WishlistRepository wishlist,
                                ProductClient productClient, Clock clock) {
        this.shares = shares;
        this.wishlist = wishlist;
        this.productClient = productClient;
        this.clock = clock;
    }

    // Idempotent: asking again returns the existing link rather than minting a second one.
    @Transactional
    public String getOrCreateToken(long phno) {
        validatePhno(phno);
        return shares.findByCustomerPhno(phno).map(WishlistShare::getToken).orElseGet(() -> {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            WishlistShare share = new WishlistShare();
            share.setToken(HexFormat.of().formatHex(bytes));
            share.setCustomerPhno(phno);
            share.setCreatedAt(Instant.now(clock));
            return shares.save(share).getToken();
        });
    }

    public boolean hasLink(long phno) {
        validatePhno(phno);
        return shares.findByCustomerPhno(phno).isPresent();
    }

    // Revoking kills the old link for good; sharing again mints a brand-new token.
    @Transactional
    public void revoke(long phno) {
        validatePhno(phno);
        shares.deleteByCustomerPhno(phno);
    }

    // A malformed or unknown/revoked token is a plain 404 - no hint which of the two it was.
    public List<SharedWishlistItem> view(String token) {
        WishlistShare share = token == null || !token.matches("[0-9a-f]{" + TOKEN_BYTES * 2 + "}") ? null
                : shares.findById(token).orElse(null);
        if (share == null) {
            throw new OrderNotFoundException("This wishlist link is not valid any more");
        }
        List<SharedWishlistItem> items = new ArrayList<>();
        for (Wishlist entry : wishlist.findByCustomerPhno(share.getCustomerPhno())) {
            Product product;
            try {
                product = productClient.getProductById(entry.getProductId());
            } catch (FeignException e) {
                continue;
            }
            if (product == null) {
                continue;
            }
            items.add(new SharedWishlistItem(product.getProductId(), product.getProductName(),
                    product.getProductCategory(), product.getProductPrice(), product.getProductImageUrl(),
                    product.getProductStock() > 0));
        }
        return items;
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
