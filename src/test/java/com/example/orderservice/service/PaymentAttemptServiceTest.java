package com.example.orderservice.service;

import com.example.orderservice.entity.PaymentAttempt;
import com.example.orderservice.entity.PaymentAttempt.Status;
import com.example.orderservice.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentAttemptServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private PaymentAttemptRepository repo;
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentAttemptRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new PaymentAttemptService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theHappyPathMovesStartedToChargedToCompleted() {
        PaymentAttempt a = service.begin(9876543210L, 499.0, "key-1");
        assertEquals(Status.STARTED, a.getStatus());

        service.charged(a, 77L);
        assertEquals(Status.CHARGED, a.getStatus());
        assertEquals(77L, a.getPaymentTransactionId());

        service.completed(a, 5L);
        assertEquals(Status.COMPLETED, a.getStatus());
        assertEquals(5L, a.getOrderId());
    }

    @Test
    void aRollbackIsRecordedAsRefundedOrFailed() {
        PaymentAttempt ok = service.begin(9876543210L, 1, null);
        service.rolledBack(ok, 1L, true);
        PaymentAttempt bad = service.begin(9876543210L, 1, null);
        service.rolledBack(bad, 2L, false);

        assertEquals(Status.ROLLED_BACK, ok.getStatus());
        assertEquals(Status.ROLLBACK_FAILED, bad.getStatus());
    }

    @Test
    void aProblemRecordingNeverFailsTheCheckout() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));

        assertNull(service.begin(9876543210L, 1, null));
        service.charged(null, 1L);          // null attempt (could not be recorded) is ignored
        service.completed(null, 1L);
    }

    @Test
    void onlyAttemptsOlderThanTheGracePeriodAreFlagged() {
        service.chargedWithoutOrder();
        service.outcomeUnknown();

        org.mockito.Mockito.verify(repo).findByStatusAndResolvedAtIsNullAndUpdatedAtBeforeOrderByIdAsc(Status.CHARGED, NOW.minus(PaymentAttemptService.NO_ORDER_AFTER));
        org.mockito.Mockito.verify(repo).findByStatusAndResolvedAtIsNullAndUpdatedAtBeforeOrderByIdAsc(Status.STARTED, NOW.minus(PaymentAttemptService.UNKNOWN_AFTER));
    }

    @Test
    void resolvingNeedsANote() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.resolve(1L, " "));

        PaymentAttempt a = new PaymentAttempt();
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(a));
        PaymentAttempt done = service.resolve(1L, "refunded by hand");
        assertNotNull(done.getResolvedAt());
        assertEquals("refunded by hand", done.getResolution());
    }
}
