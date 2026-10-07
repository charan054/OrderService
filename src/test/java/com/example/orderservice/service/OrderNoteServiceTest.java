package com.example.orderservice.service;

import com.example.orderservice.entity.OrderNote;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderNoteServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private OrderNoteRepository notes;
    @Mock
    private CartRepository orders;

    private OrderNoteService service;

    @BeforeEach
    void setUp() {
        service = new OrderNoteService(notes, orders, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void addTrimsStampsAndSaves() {
        when(orders.existsById(7L)).thenReturn(true);
        when(notes.save(any(OrderNote.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderNote n = service.add(7, "  Customer called - reschedule  ");

        assertEquals(7L, n.getOrderId());
        assertEquals("Customer called - reschedule", n.getNote());
        assertEquals(NOW, n.getCreatedAt());
    }

    @Test
    void addRejectsBlankTooLongAndUnknownOrder() {
        assertThrows(ProductException.class, () -> service.add(7, "   "));
        assertThrows(ProductException.class, () -> service.add(7, null));
        assertThrows(ProductException.class, () -> service.add(7, "x".repeat(501)));
        when(orders.existsById(8L)).thenReturn(false);
        assertThrows(OrderNotFoundException.class, () -> service.add(8, "hello"));
        verify(notes, never()).save(any());
    }

    @Test
    void deleteRejectsUnknownAndDeletesKnown() {
        when(notes.existsById(1L)).thenReturn(false);
        when(notes.existsById(2L)).thenReturn(true);

        assertThrows(OrderNotFoundException.class, () -> service.delete(1));
        service.delete(2);

        verify(notes).deleteById(2L);
        verify(notes, never()).deleteById(1L);
    }
}
