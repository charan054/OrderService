package com.example.orderservice.service;

import com.example.orderservice.dto.RefundStatus;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefundStatusServiceTest {
    private static final long PHNO = 9876543210L;
    private final CartRepository orders = mock(CartRepository.class);
    private final StoreCreditTransactionRepository credits = mock(StoreCreditTransactionRepository.class);
    private final TrackingEventRepository tracking = mock(TrackingEventRepository.class);
    private final RefundStatusService service = new RefundStatusService(orders, credits, tracking);

    private Cart order(PaymentMethod method, OrderStatus status, double total, double refunded, double creditUsedBack) {
        Cart c = new Cart();
        c.setOrderId(5L);
        c.setCustomerPhno(PHNO);
        c.setPaymentMethod(method);
        c.setStatus(status);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        c.setStoreCreditRefunded(creditUsedBack);
        when(orders.findById(5L)).thenReturn(Optional.of(c));
        return c;
    }

    private StoreCreditTransaction credit(double amount) {
        StoreCreditTransaction t = new StoreCreditTransaction();
        t.setAmount(amount);
        t.setCreatedAt(Instant.parse("2026-10-12T10:00:00Z"));
        t.setNote("Refund");
        return t;
    }

    @Test
    void aPhonePeRefundIsReportedAsGoneBackToPhonePe() {
        order(PaymentMethod.PHONEPE, OrderStatus.CANCELLED, 500, 500, 0);
        when(credits.findByOrderIdAndTypeOrderByIdAsc(5L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of());
        TrackingEvent e = new TrackingEvent();
        e.setStatus(OrderStatus.CANCELLED);
        e.setTimestamp(Instant.parse("2026-10-12T09:00:00Z"));
        when(tracking.findByOrderIdOrderByTimestampAsc(5L)).thenReturn(List.of(e));

        RefundStatus s = service.statusOf(5L, PHNO);

        assertEquals(500, s.toPhonePe());
        assertEquals(0, s.toStoreCredit());
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), s.lastChangeAt());
        assertTrue(s.summary().contains("refunded to your PhonePe account"));
    }

    @Test
    void aRefundTakenAsStoreCreditIsNotAlsoCountedAsPhonePe() {
        order(PaymentMethod.PHONEPE, OrderStatus.CANCELLED, 400, 400, 0);
        when(credits.findByOrderIdAndTypeOrderByIdAsc(5L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of(credit(400)));
        when(tracking.findByOrderIdOrderByTimestampAsc(5L)).thenReturn(List.of());

        RefundStatus s = service.statusOf(5L, PHNO);

        assertEquals(0, s.toPhonePe());
        assertEquals(400, s.toStoreCredit());
        assertEquals(1, s.entries().size());
    }

    @Test
    void storeCreditUsedAtCheckoutComesBackSeparately() {
        order(PaymentMethod.PHONEPE, OrderStatus.CANCELLED, 300, 300, 200);
        when(credits.findByOrderIdAndTypeOrderByIdAsc(5L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of(credit(200)));
        when(tracking.findByOrderIdOrderByTimestampAsc(5L)).thenReturn(List.of());

        RefundStatus s = service.statusOf(5L, PHNO);

        assertEquals(300, s.toPhonePe());
        assertEquals(0, s.toStoreCredit());
        assertEquals(200, s.storeCreditReturned());
    }

    @Test
    void anUncollectedCashOrderIsNoLongerDueNotRefunded() {
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 250, 250, 0);
        when(credits.findByOrderIdAndTypeOrderByIdAsc(5L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of());
        when(tracking.findByOrderIdOrderByTimestampAsc(5L)).thenReturn(List.of());

        RefundStatus s = service.statusOf(5L, PHNO);

        assertEquals(250, s.noLongerDue());
        assertEquals(0, s.toPhonePe());
        assertTrue(s.summary().contains("no longer due"));
    }

    @Test
    void anotherCustomersOrderIsNotFound() {
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 250, 250, 0);

        assertThrows(OrderNotFoundException.class, () -> service.statusOf(5L, 9000000001L));
    }
}
