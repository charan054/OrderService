package com.example.orderservice.service;

import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.SavedCartRepository;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedCartServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private SavedCartRepository carts;

    private SavedCartService service;

    @BeforeEach
    void setUp() {
        service = new SavedCartService(carts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SavedCart.Line line(int productId, int quantity) {
        return new SavedCart.Line(productId, quantity);
    }

    @Test
    void getReturnsAnEmptyCartWhenNothingIsSaved() {
        when(carts.findById(PHNO)).thenReturn(Optional.empty());

        SavedCart cart = service.get(PHNO);

        assertEquals(PHNO, cart.getPhno());
        assertTrue(cart.getLines().isEmpty());
    }

    @Test
    void replaceMergesDuplicateLinesAndCapsQuantity() {
        when(carts.findById(PHNO)).thenReturn(Optional.empty());
        when(carts.save(any(SavedCart.class))).thenAnswer(inv -> inv.getArgument(0));

        SavedCart saved = service.replace(PHNO, List.of(line(1, 2), line(2, 1), line(1, 3), line(3, 80), line(3, 80)));

        assertEquals(3, saved.getLines().size());
        assertEquals(5, saved.getLines().get(0).getQuantity());
        assertEquals(99, saved.getLines().get(2).getQuantity());
        assertEquals(NOW, saved.getUpdatedAt());
    }

    @Test
    void replaceOverwritesAnExistingCart() {
        SavedCart existing = new SavedCart();
        existing.setPhno(PHNO);
        existing.getLines().add(line(9, 9));
        when(carts.findById(PHNO)).thenReturn(Optional.of(existing));
        when(carts.save(any(SavedCart.class))).thenAnswer(inv -> inv.getArgument(0));

        SavedCart saved = service.replace(PHNO, List.of(line(1, 1)));

        assertEquals(List.of(line(1, 1)), saved.getLines());
    }

    @Test
    void replacingWithNoLinesDeletesTheSavedCart() {
        when(carts.findById(PHNO)).thenReturn(Optional.empty());

        SavedCart result = service.replace(PHNO, List.of());

        verify(carts).deleteById(PHNO);
        verify(carts, never()).save(any());
        assertTrue(result.getLines().isEmpty());
    }

    @Test
    void replaceRejectsBadLinesTooManyLinesAndBadPhones() {
        assertThrows(ProductException.class, () -> service.replace(PHNO, List.of(line(0, 1))));
        assertThrows(ProductException.class, () -> service.replace(PHNO, List.of(line(1, 0))));
        assertThrows(ProductException.class, () -> service.replace(PHNO, null));
        List<SavedCart.Line> tooMany = IntStream.rangeClosed(1, 51).mapToObj(i -> line(i, 1)).toList();
        assertThrows(ProductException.class, () -> service.replace(PHNO, tooMany));
        assertThrows(ProductException.class, () -> service.replace(123L, List.of(line(1, 1))));
        assertThrows(ProductException.class, () -> service.get(123L));
        verify(carts, never()).save(any());
    }
}
