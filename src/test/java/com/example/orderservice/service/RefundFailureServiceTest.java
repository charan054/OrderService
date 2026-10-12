package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.entity.RefundFailure;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.PaymentException;
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
    private final PhonepeClient phonepe = mock(PhonepeClient.class);
    private final StoreCreditService credit = mock(StoreCreditService.class);
    private final CustomerNotifier notifier = mock(CustomerNotifier.class);
    private final RefundFailureService service = new RefundFailureService(repo, phonepe, credit, notifier, Clock.fixed(NOW, ZoneOffset.UTC), "key");

    private RefundFailure open(String kind, Long txId) {
        RefundFailure f = new RefundFailure();
        f.setId(3L);
        f.setOrderId(7);
        f.setCustomerPhno(9876543210L);
        f.setKind(kind);
        f.setAmount(120.5);
        f.setPaymentTransactionId(txId);
        when(repo.findById(3L)).thenReturn(Optional.of(f));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return f;
    }

    private PaymentResponse refundResponse(String status) {
        return new PaymentResponse(100001L, "Refund", "CREDIT", 9876543210L, null, new java.math.BigDecimal("120.50"), status,
                NOW, "Refund");
    }

    @Test
    void retryRefundsThroughTheMerchantEndpointOnceAndResolves() {
        RefundFailure f = open("PHONEPE_REFUND", 55L);
        when(phonepe.merchantRefund(org.mockito.ArgumentMatchers.eq("key"), org.mockito.ArgumentMatchers.eq(55L), any(RefundRequest.class)))
                .thenReturn(refundResponse("COMPLETED"));

        RefundFailure done = service.retry(3);

        assertNotNull(done.getResolvedAt());
        verify(phonepe).merchantRefund("key", 55L, new RefundRequest("settle-refund-failure-3", null));
        verify(notifier).notifyRefundSettled(9876543210L, 7L, 120.5, false);
        assertEquals(f, done);
    }

    @Test
    void aRefundThatIsNotCompletedStaysOpen() {
        RefundFailure f = open("PHONEPE_REFUND", 55L);
        when(phonepe.merchantRefund(any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(refundResponse("NEEDS_RECONCILIATION"));

        assertThrows(PaymentException.class, () -> service.retry(3));
        assertEquals(null, f.getResolvedAt());
        org.mockito.Mockito.verifyNoInteractions(notifier);
    }

    @Test
    void aStoreCreditFailureIsCreditedOnRetry() {
        open("STORE_CREDIT", null);

        RefundFailure done = service.retry(3);

        assertNotNull(done.getResolvedAt());
        verify(credit).credit(9876543210L, 120.5, StoreCreditTransaction.Type.REVERSED, 7L,
                "Order could not be completed - store credit returned (settled by staff)");
        verify(notifier).notifyRefundSettled(9876543210L, 7L, 120.5, true);
    }

    @Test
    void aNotifierFailureNeverUndoesASettledRefund() {
        open("STORE_CREDIT", null);
        org.mockito.Mockito.doThrow(new RuntimeException("mail down")).when(notifier)
                .notifyRefundSettled(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyBoolean());

        RefundFailure done = service.retry(3);

        assertNotNull(done.getResolvedAt());
    }

    @Test
    void aPhonePeFailureWithNoPaymentCannotBeRetried() {
        open("PHONEPE_REFUND", null);

        assertThrows(ProductException.class, () -> service.retry(3));
    }

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
