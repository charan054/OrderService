package com.example.orderservice.service;

import com.example.orderservice.entity.RefundFailure;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.RefundFailureRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundFailureServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private final RefundFailureRepository repo = mock(RefundFailureRepository.class);
    private final RefundFailureService service = new RefundFailureService(repo, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordSavesAnOpenFailure() {
        service.record(7, 9876543210L, "PHONEPE_REFUND", 120.5, 55L, "bank down");

        ArgumentCaptor<RefundFailure> captor = ArgumentCaptor.forClass(RefundFailure.class);
        verify(repo).save(captor.capture());
        assertEquals(7, captor.getValue().getOrderId());
        assertEquals(NOW, captor.getValue().getCreatedAt());
        assertEquals(null, captor.getValue().getResolvedAt());
    }

    @Test
    void recordNeverThrowsEvenIfTheDatabaseDoes() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));

        service.record(7, 9876543210L, "STORE_CREDIT", 10, null, "x");
    }

    @Test
    void resolveNeedsANoteAndOnlyWorksOnce() {
        RefundFailure f = new RefundFailure();
        f.setId(3L);
        when(repo.findById(3L)).thenReturn(Optional.of(f));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThrows(ProductException.class, () -> service.resolve(3, "  "));
        RefundFailure done = service.resolve(3, "refunded by hand");
        assertNotNull(done.getResolvedAt());
        assertEquals("refunded by hand", done.getResolution());
        assertThrows(ProductException.class, () -> service.resolve(3, "again"));
    }
}
