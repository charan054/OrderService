package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.DeliveryAttempt;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.DeliveryAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryFailureServiceTest {
    @Mock
    private CartRepository orders;
    @Mock
    private DeliveryAttemptRepository attempts;
    @Mock
    private OrderService orderService;
    @Mock
    private CustomerNotifier notifier;

    private DeliveryFailureService service;
    private Cart cart;

    @BeforeEach
    void setUp() {
        service = new DeliveryFailureService(orders, attempts, orderService, notifier,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T10:00:00Z"), java.time.ZoneOffset.UTC), 3);
        cart = new Cart();
        cart.setOrderId(9L);
        cart.setStatus(OrderStatus.SHIPPED);
        lenient().when(orders.findById(9L)).thenReturn(Optional.of(cart));
        lenient().when(attempts.save(any(DeliveryAttempt.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void recordNumbersTheAttemptTellsTheCustomerAndLeavesTheOrderShipped() {
        when(attempts.countByOrderId(9L)).thenReturn(1L);

        var result = service.record(9L, "customer_unavailable", " nobody home ");

        assertEquals(2, result.attemptsSoFar());
        assertEquals("CUSTOMER_UNAVAILABLE", result.attempt().getReason());
        assertEquals("nobody home", result.attempt().getNote());
        assertFalse(result.giveUpAdvised());
        assertEquals(OrderStatus.SHIPPED, cart.getStatus());
        verify(notifier).notifyOrderUpdate(any(Cart.class), any(), any());
    }

    @Test
    void theThirdFailureAdvisesGivingUp() {
        when(attempts.countByOrderId(9L)).thenReturn(2L);
        assertTrue(service.record(9L, "REFUSED", null).giveUpAdvised());
    }

    @Test
    void recordRefusesAnUnknownReasonAndAnOrderThatIsNotShipped() {
        assertThrows(ProductException.class, () -> service.record(9L, "BAD", null));
        cart.setStatus(OrderStatus.PLACED);
        assertThrows(ProductException.class, () -> service.record(9L, "OTHER", null));
        verify(attempts, never()).save(any(DeliveryAttempt.class));
    }

    @Test
    void returnToStockNeedsAFailedAttemptFirst() {
        when(attempts.countByOrderId(9L)).thenReturn(0L);
        assertThrows(ProductException.class, () -> service.returnToStock(9L, null));
        verify(orderService, never()).cancelUndelivered(anyLong(), any());

        when(attempts.countByOrderId(9L)).thenReturn(1L);
        service.returnToStock(9L, null);
        verify(orderService).cancelUndelivered(9L, "Could not be delivered");
    }

    @Test
    void markDelayedStoresTheNewDateAndTellsTheCustomer() {
        when(orders.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        Cart result = service.markDelayed(9L, java.time.LocalDate.parse("2026-10-20"), " courier backlog ");

        assertEquals(java.time.LocalDate.parse("2026-10-20"), result.getExpectedDeliveryDate());
        assertEquals("courier backlog", result.getDelayReason());
        verify(notifier).notifyOrderUpdate(any(Cart.class), any(), any());
    }

    @Test
    void markDelayedNeedsAShippedOrderAFutureDateAndAReason() {
        java.time.LocalDate ok = java.time.LocalDate.parse("2026-10-20");
        assertThrows(ProductException.class, () -> service.markDelayed(9L, java.time.LocalDate.parse("2026-10-01"), "x"));
        assertThrows(ProductException.class, () -> service.markDelayed(9L, ok, " "));
        assertThrows(ProductException.class, () -> service.markDelayed(9L, null, "x"));
        cart.setStatus(OrderStatus.PLACED);
        assertThrows(ProductException.class, () -> service.markDelayed(9L, ok, "x"));
        verify(orders, never()).save(any(Cart.class));
    }
}
