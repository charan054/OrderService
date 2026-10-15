package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.QuantityBreak;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.QuantityBreakRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuantityBreakServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @Mock
    private QuantityBreakRepository breaks;
    @Mock
    private ProductClient productClient;

    private QuantityBreakService service;

    @BeforeEach
    void setUp() {
        service = new QuantityBreakService(breaks, productClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static QuantityBreak tier(int productId, int min, int percent) {
        QuantityBreak b = new QuantityBreak();
        b.setProductId(productId);
        b.setMinQuantity(min);
        b.setPercentOff(percent);
        return b;
    }

    private void productExists() {
        when(productClient.getProductById(1)).thenReturn(new Product());
    }

    @Test
    void highestReachedTierAppliesToEveryUnitOfThatProductOnly() {
        List<QuantityBreak> tiers = List.of(tier(1, 3, 5), tier(1, 10, 10), tier(2, 2, 50));
        double saving = QuantityBreakService.discountFor(tiers, Map.of(1, 10, 2, 1), Map.of(1, 100.0, 2, 80.0));
        // product 1: 10 units x 100 x 10% = 100; product 2 has only 1 unit, below its 2+ break
        assertEquals(100.0, saving);
    }

    @Test
    void middleTierWhenTheTopOneIsNotReached() {
        double saving = QuantityBreakService.discountFor(List.of(tier(1, 3, 5), tier(1, 10, 10)), Map.of(1, 4), Map.of(1, 50.0));
        assertEquals(10.0, saving);
    }

    @Test
    void noBreaksOrNoPriceMeansNoSaving() {
        assertEquals(0.0, QuantityBreakService.discountFor(List.of(), Map.of(1, 5), Map.of(1, 50.0)));
        assertEquals(0.0, QuantityBreakService.discountFor(List.of(tier(1, 2, 10)), Map.of(1, 5), Map.of()));
    }

    @Test
    void createStoresAnActiveBreak() {
        productExists();
        when(breaks.save(any(QuantityBreak.class))).thenAnswer(i -> i.getArgument(0));

        QuantityBreak b = service.create(1, 3, 10);

        assertEquals(3, b.getMinQuantity());
        assertEquals(10, b.getPercentOff());
        assertEquals(NOW, b.getCreatedAt());
        assertEquals(true, b.isActive());
    }

    @Test
    void badNumbersAndUnknownProductsAreRejected() {
        assertThrows(ProductException.class, () -> service.create(1, 1, 10));
        assertThrows(ProductException.class, () -> service.create(1, 3, 0));
        assertThrows(ProductException.class, () -> service.create(1, 3, 91));
        when(productClient.getProductById(1)).thenReturn(null);
        assertThrows(ProductException.class, () -> service.create(1, 3, 10));
        verify(breaks, never()).save(any());
    }

    @Test
    void theSameTierTwiceIsRejected() {
        productExists();
        when(breaks.existsByProductIdAndMinQuantityAndActiveTrue(1, 3)).thenReturn(true);
        assertThrows(ProductException.class, () -> service.create(1, 3, 10));
    }

    @Test
    void aBiggerQuantityMustSaveMore() {
        productExists();
        when(breaks.findByActiveTrueOrderByProductIdAscMinQuantityAsc()).thenReturn(List.of(tier(1, 3, 10)));
        // 10+ at only 5% would make a big order dearer per unit than a small one
        assertThrows(ProductException.class, () -> service.create(1, 10, 5));
        // 2+ at 15% would make a small order cheaper than the 3+ one
        assertThrows(ProductException.class, () -> service.create(1, 2, 15));
        verify(breaks, never()).save(any());
    }

    @Test
    void retireDeactivatesRatherThanDeletes() {
        QuantityBreak b = tier(1, 3, 10);
        when(breaks.findById(5L)).thenReturn(Optional.of(b));

        service.retire(5);

        assertFalse(b.isActive());
        verify(breaks).save(b);
        verify(breaks, never()).deleteById(any());
    }

    @Test
    void retiringAnUnknownBreakFails() {
        when(breaks.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.retire(9));
    }
}
