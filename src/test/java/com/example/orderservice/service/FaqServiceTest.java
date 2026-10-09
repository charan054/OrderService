package com.example.orderservice.service;

import com.example.orderservice.entity.Faq;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.FaqRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaqServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Mock
    private FaqRepository faqs;

    private FaqService service;

    @BeforeEach
    void setUp() {
        service = new FaqService(faqs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Faq input(Long id, String question, String answer, int order) {
        Faq f = new Faq();
        f.setId(id);
        f.setQuestion(question);
        f.setAnswer(answer);
        f.setSortOrder(order);
        return f;
    }

    @Test
    void addsANewEntryTrimmedAndTimestamped() {
        when(faqs.count()).thenReturn(3L);
        when(faqs.save(any(Faq.class))).thenAnswer(i -> i.getArgument(0));

        Faq saved = service.save(input(null, "  Where? ", " Here. ", 5));

        assertEquals("Where?", saved.getQuestion());
        assertEquals("Here.", saved.getAnswer());
        assertEquals(5, saved.getSortOrder());
        assertEquals(NOW, saved.getUpdatedAt());
    }

    @Test
    void editsTheExistingEntryWhenAnIdIsGiven() {
        Faq existing = input(7L, "Old?", "Old.", 1);
        when(faqs.findById(7L)).thenReturn(Optional.of(existing));
        when(faqs.save(existing)).thenReturn(existing);

        Faq saved = service.save(input(7L, "New?", "New.", 2));

        assertSame(existing, saved);
        assertEquals("New?", saved.getQuestion());
        verify(faqs, never()).count();
    }

    @Test
    void editingAMissingEntryIs404() {
        when(faqs.findById(9L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.save(input(9L, "Q?", "A.", 0)));
    }

    @Test
    void rejectsBlankOrOverlongText() {
        assertThrows(ProductException.class, () -> service.save(input(null, " ", "A.", 0)));
        assertThrows(ProductException.class, () -> service.save(input(null, "Q?", null, 0)));
        assertThrows(ProductException.class, () -> service.save(input(null, "q".repeat(201), "A.", 0)));
        assertThrows(ProductException.class, () -> service.save(input(null, "Q?", "a".repeat(2001), 0)));
        assertThrows(ProductException.class, () -> service.save(null));
        verify(faqs, never()).save(any());
    }

    @Test
    void refusesANewEntryOnceTheListIsFull() {
        when(faqs.count()).thenReturn(100L);
        assertThrows(ProductException.class, () -> service.save(input(null, "Q?", "A.", 0)));
    }

    @Test
    void deleteRemovesAnExistingEntryAndRejectsAMissingOne() {
        when(faqs.existsById(1L)).thenReturn(true);
        when(faqs.existsById(2L)).thenReturn(false);

        service.delete(1L);

        verify(faqs).deleteById(1L);
        assertThrows(OrderNotFoundException.class, () -> service.delete(2L));
    }
}
