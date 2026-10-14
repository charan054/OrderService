package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.BundleView;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Bundle;
import com.example.orderservice.entity.BundleItem;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.BundleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BundleServiceTest {
    @Mock
    private BundleRepository bundles;
    @Mock
    private ProductClient productClient;

    private BundleService service;

    @BeforeEach
    void setUp() {
        service = new BundleService(bundles, productClient, Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC));
    }

    private Product product(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("P" + id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    private Bundle bundle(long id, double price, int... productAndQty) {
        Bundle b = new Bundle();
        b.setId(id);
        b.setName("B" + id);
        b.setBundlePrice(price);
        for (int i = 0; i < productAndQty.length; i += 2) {
            BundleItem item = new BundleItem();
            item.setProductId(productAndQty[i]);
            item.setQuantity(productAndQty[i + 1]);
            b.getItems().add(item);
        }
        return b;
    }

    private Map<Integer, Integer> qty(int... pairs) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    // ---------- discountFor ----------

    @Test
    void discountIsPerCompleteSet() {
        Bundle b = bundle(1, 150, 1, 2, 2, 1); // 2 x P1 (100) + 1 x P2 (50) = 250, bundle 150 -> saves 100
        Map<Integer, Double> prices = Map.of(1, 100.0, 2, 50.0);

        assertEquals(0.0, BundleService.discountFor(List.of(b), qty(1, 1, 2, 1), prices));
        assertEquals(100.0, BundleService.discountFor(List.of(b), qty(1, 2, 2, 1), prices));
        assertEquals(100.0, BundleService.discountFor(List.of(b), qty(1, 3, 2, 1), prices), "the spare unit is not a set");
        assertEquals(200.0, BundleService.discountFor(List.of(b), qty(1, 4, 2, 2), prices));
    }

    @Test
    void aUnitIsOnlyUsedByOneBundle() {
        Bundle big = bundle(1, 100, 1, 1, 2, 1);   // parts 200 -> saves 100
        Bundle small = bundle(2, 150, 1, 1, 3, 1); // parts 200 -> saves 50
        Map<Integer, Double> prices = Map.of(1, 100.0, 2, 100.0, 3, 100.0);

        double saved = BundleService.discountFor(List.of(small, big), qty(1, 1, 2, 1, 3, 1), prices);

        assertEquals(100.0, saved, "product 1 is spent on the bigger saving, so the second bundle has no unit left");
    }

    @Test
    void aBundleThatSavesNothingIsSkipped() {
        Bundle b = bundle(1, 150, 1, 1, 2, 1);
        // a sale has already made the parts cost 140 together
        assertEquals(0.0, BundleService.discountFor(List.of(b), qty(1, 1, 2, 1), Map.of(1, 70.0, 2, 70.0)));
    }

    @Test
    void anUnpricedProductMeansNoDiscount() {
        assertEquals(0.0, BundleService.discountFor(List.of(bundle(1, 10, 1, 1, 2, 1)), qty(1, 1), Map.of(1, 100.0)));
    }

    // ---------- create ----------

    @Test
    void createSavesTheBundleWithItsItems() {
        when(productClient.getProductById(1)).thenReturn(product(1, 100, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 50, 5));
        when(bundles.save(any(Bundle.class))).thenAnswer(inv -> inv.getArgument(0));

        Bundle saved = service.create("  Duo ", 120, qty(1, 1, 2, 1));

        assertEquals("Duo", saved.getName());
        assertEquals(2, saved.getItems().size());
        assertTrue(saved.isActive());
    }

    @Test
    void createRejectsBadInput() {
        lenient().when(productClient.getProductById(1)).thenReturn(product(1, 100, 5));
        lenient().when(productClient.getProductById(2)).thenReturn(product(2, 50, 5));

        assertThrows(ProductException.class, () -> service.create("", 120, qty(1, 1, 2, 1)));
        assertThrows(ProductException.class, () -> service.create("Duo", 0, qty(1, 1, 2, 1)));
        assertThrows(ProductException.class, () -> service.create("Duo", 120, qty(1, 1)), "one product is not a bundle");
        assertThrows(ProductException.class, () -> service.create("Duo", 120, qty(1, 0, 2, 1)));
        assertThrows(ProductException.class, () -> service.create("Duo", 150, qty(1, 1, 2, 1)), "not cheaper than the parts");
        verify(bundles, never()).save(any());
    }

    // ---------- listActive ----------

    @Test
    void listShowsLivePricesSavingsAndAvailability() {
        when(bundles.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(bundle(1, 120, 1, 1, 2, 2)));
        when(productClient.getProductById(1)).thenReturn(product(1, 100, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 50, 1)); // needs 2, only 1 in stock

        List<BundleView> views = service.listActive();

        assertEquals(1, views.size());
        assertEquals(200.0, views.get(0).regularTotal());
        assertEquals(80.0, views.get(0).savings());
        assertFalse(views.get(0).available());
    }

    @Test
    void listLeavesOutABundleWhoseProductIsGone() {
        when(bundles.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(bundle(1, 120, 1, 1, 2, 1)));
        when(productClient.getProductById(1)).thenReturn(null);

        assertTrue(service.listActive().isEmpty());
    }

    @Test
    void retireDeactivates() {
        Bundle b = bundle(1, 120, 1, 1, 2, 1);
        when(bundles.findById(1L)).thenReturn(java.util.Optional.of(b));

        service.retire(1);

        assertFalse(b.isActive());
        verify(bundles).save(b);
    }
}
