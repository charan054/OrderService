package com.example.orderservice.service;

import com.example.orderservice.dto.CodEligibility;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CodOverride;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CodOverrideRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodRiskServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Mock
    private CartRepository orders;
    @Mock
    private TrackingEventRepository trackingEvents;
    @Mock
    private CodOverrideRepository overrides;

    private CodRiskService service;
    private final List<Cart> history = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        service = new CodRiskService(orders, trackingEvents, overrides, Clock.fixed(NOW, ZoneOffset.UTC),
                true, 3, 180, 2000);
        lenient().when(orders.findBycustomerPhno(PHNO)).thenReturn(history);
        lenient().when(overrides.findById(PHNO)).thenReturn(Optional.empty());
        lenient().when(trackingEvents.findByOrderIdOrderByTimestampAsc(anyLong())).thenReturn(List.of());
    }

    private void order(PaymentMethod method, OrderStatus status, Integer placedDaysAgo) {
        Cart c = new Cart();
        long id = nextId++;
        c.setOrderId(id);
        c.setCustomerPhno(PHNO);
        c.setPaymentMethod(method);
        c.setStatus(status);
        history.add(c);
        if (placedDaysAgo != null) {
            TrackingEvent e = new TrackingEvent();
            e.setOrderId(id);
            e.setStatus(OrderStatus.PLACED);
            e.setTimestamp(NOW.minus(placedDaysAgo, ChronoUnit.DAYS));
            lenient().when(trackingEvents.findByOrderIdOrderByTimestampAsc(id)).thenReturn(List.of(e));
        }
    }

    private void override(CodOverride.Mode mode) {
        CodOverride o = new CodOverride();
        o.setPhno(PHNO);
        o.setMode(mode);
        o.setNote("checked by phone");
        when(overrides.findById(PHNO)).thenReturn(Optional.of(o));
    }

    @Test
    void aBrandNewCustomerMayPayCashUpToTheFirstOrderCap() {
        CodEligibility e = service.check(PHNO);

        assertTrue(e.available());
        assertEquals(2000.0, e.maxAmount());
        assertDoesNotThrow(() -> service.assertCashAllowed(PHNO, 2000));
        ProductException ex = assertThrows(ProductException.class, () -> service.assertCashAllowed(PHNO, 2000.01));
        assertTrue(ex.getMessage().contains("Rs. 2000.00"));
    }

    @Test
    void theCapGoesAwayOnceAnOrderHasBeenDelivered() {
        order(PaymentMethod.PHONEPE, OrderStatus.DELIVERED, 10);

        CodEligibility e = service.check(PHNO);

        assertTrue(e.available());
        assertNull(e.maxAmount());
        assertTrue(e.hasDeliveredOrder());
        assertDoesNotThrow(() -> service.assertCashAllowed(PHNO, 50000));
    }

    @Test
    void enoughRecentFailedCashOrdersBlockCash() {
        order(PaymentMethod.PHONEPE, OrderStatus.DELIVERED, 100);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 5);
        order(PaymentMethod.CASH, OrderStatus.RETURNED, 20);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 179);

        CodEligibility e = service.check(PHNO);

        assertFalse(e.available());
        assertEquals(3, e.failedCashOrders());
        assertThrows(ProductException.class, () -> service.assertCashAllowed(PHNO, 10));
    }

    @Test
    void onlyCashFailuresInsideTheWindowCount() {
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 5);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 6);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 200);      // outside the 180-day window
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, null);     // undated (pre-tracking)
        order(PaymentMethod.PHONEPE, OrderStatus.CANCELLED, 1);     // paid online - not a cash risk
        order(PaymentMethod.CASH, OrderStatus.DELIVERED, 3);        // a good cash order

        CodEligibility e = service.check(PHNO);

        assertTrue(e.available());
        assertEquals(2, e.failedCashOrders());
    }

    @Test
    void anAllowOverrideBeatsTheRulesAndTheCap() {
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 1);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 2);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 3);
        override(CodOverride.Mode.ALLOW);

        CodEligibility e = service.check(PHNO);

        assertTrue(e.available());
        assertNull(e.maxAmount());
        assertEquals("ALLOW", e.override());
        assertEquals("checked by phone", e.overrideNote());
    }

    @Test
    void aBlockOverrideRefusesCashEvenForAGoodCustomer() {
        order(PaymentMethod.CASH, OrderStatus.DELIVERED, 3);
        override(CodOverride.Mode.BLOCK);

        assertFalse(service.check(PHNO).available());
        assertThrows(ProductException.class, () -> service.assertCashAllowed(PHNO, 1));
    }

    @Test
    void turningTheRulesOffLeavesCashOpenButOverridesStillApply() {
        service = new CodRiskService(orders, trackingEvents, overrides, Clock.fixed(NOW, ZoneOffset.UTC),
                false, 3, 180, 2000);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 1);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 2);
        order(PaymentMethod.CASH, OrderStatus.CANCELLED, 3);

        CodEligibility e = service.check(PHNO);
        assertTrue(e.available());
        assertNull(e.maxAmount());

        override(CodOverride.Mode.BLOCK);
        assertFalse(service.check(PHNO).available());
    }

    @Test
    void settingAnOverrideStoresModeNoteAndTime() {
        when(overrides.findById(PHNO)).thenReturn(Optional.empty());

        service.setOverride(PHNO, "block", "  refused at door twice  ");

        ArgumentCaptor<CodOverride> saved = ArgumentCaptor.forClass(CodOverride.class);
        verify(overrides).save(saved.capture());
        assertEquals(CodOverride.Mode.BLOCK, saved.getValue().getMode());
        assertEquals("refused at door twice", saved.getValue().getNote());
        assertEquals(NOW, saved.getValue().getUpdatedAt());
    }

    @Test
    void autoRemovesTheOverride() {
        service.setOverride(PHNO, "AUTO", null);

        verify(overrides).deleteById(PHNO);
    }

    @Test
    void anUnknownModeIsRejected() {
        assertThrows(ProductException.class, () -> service.setOverride(PHNO, "MAYBE", null));
        assertThrows(ProductException.class, () -> service.setOverride(PHNO, "BLOCK", "x".repeat(201)));
    }
}
