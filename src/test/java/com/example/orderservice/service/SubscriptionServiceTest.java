package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.Subscription;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private OrderService orderService;
    @Mock
    private ProductClient productClient;
    @Mock
    private ShippingAddressRepository addresses;

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(subscriptions, orderService, productClient, addresses,
                Clock.fixed(NOW, ZoneOffset.UTC), 5.0);
    }

    private Subscription saved(long id, String status, Instant nextRunAt) {
        Subscription s = new Subscription();
        s.setId(id);
        s.setCustomerPhno(PHNO);
        s.setCustomerName("Asha");
        s.setProductId(1);
        s.setQuantity(2);
        s.setIntervalDays(30);
        s.setDiscountPercent(5.0);
        s.setStatus(status);
        s.setNextRunAt(nextRunAt);
        return s;
    }

    private void productExists() {
        when(productClient.getProductById(1)).thenReturn(new Product());
    }

    // ---------- create ----------

    @Test
    void createStartsActiveDueNowWithTheCurrentDiscountCopiedOn() {
        productExists();
        when(subscriptions.countByCustomerPhnoAndStatusIn(anyLong(), anyCollection())).thenReturn(0L);
        when(subscriptions.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription s = service.create(PHNO, "  Asha ", 1, 2, 30, null, null);

        assertEquals("ACTIVE", s.getStatus());
        assertEquals(NOW, s.getNextRunAt());
        assertEquals(5.0, s.getDiscountPercent());
        assertEquals("Asha", s.getCustomerName());
        assertEquals(30, s.getIntervalDays());
    }

    @Test
    void createWithStartNowFalseWaitsOneInterval() {
        productExists();
        when(subscriptions.countByCustomerPhnoAndStatusIn(anyLong(), anyCollection())).thenReturn(0L);
        when(subscriptions.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription s = service.create(PHNO, null, 1, 1, 14, null, false);

        assertEquals(NOW.plus(Duration.ofDays(14)), s.getNextRunAt());
    }

    @Test
    void createRejectsABadQuantityIntervalProductOrAddress() {
        assertThrows(ProductException.class, () -> service.create(PHNO, null, 1, 0, 30, null, null));
        assertThrows(ProductException.class, () -> service.create(PHNO, null, 1, 21, 30, null, null));
        assertThrows(ProductException.class, () -> service.create(PHNO, null, 1, 1, 10, null, null));

        when(productClient.getProductById(1)).thenReturn(null);
        assertThrows(ProductException.class, () -> service.create(PHNO, null, 1, 1, 30, null, null));

        productExists();
        ShippingAddress someoneElses = new ShippingAddress();
        someoneElses.setCustomerPhno(PHNO + 1);
        when(addresses.findById(5L)).thenReturn(Optional.of(someoneElses));
        assertThrows(OrderNotFoundException.class, () -> service.create(PHNO, null, 1, 1, 30, 5L, null));
        verify(subscriptions, never()).save(any());
    }

    @Test
    void createRefusesAnEleventhSubscription() {
        productExists();
        when(subscriptions.countByCustomerPhnoAndStatusIn(anyLong(), anyCollection())).thenReturn(10L);

        assertThrows(ProductException.class, () -> service.create(PHNO, null, 1, 1, 30, null, null));
    }

    // ---------- pause / resume / skip / cancel ----------

    @Test
    void pauseResumeSkipAndCancelChangeTheRightThings() {
        Subscription s = saved(1, "ACTIVE", NOW.plus(Duration.ofDays(3)));
        when(subscriptions.findById(1L)).thenReturn(Optional.of(s));

        assertEquals("PAUSED", service.pause(1, PHNO).getStatus());

        s.setConsecutiveFailures(3);
        s.setLastError("boom");
        s.setNextRunAt(NOW.minus(Duration.ofDays(2)));
        Subscription resumed = service.resume(1, PHNO);
        assertEquals("ACTIVE", resumed.getStatus());
        assertEquals(0, resumed.getConsecutiveFailures());
        assertNull(resumed.getLastError());
        assertEquals(NOW, resumed.getNextRunAt());

        s.setNextRunAt(NOW.plus(Duration.ofDays(3)));
        assertEquals(NOW.plus(Duration.ofDays(33)), service.skipNext(1, PHNO).getNextRunAt());

        assertEquals("CANCELLED", service.cancel(1, PHNO).getStatus());
    }

    @Test
    void someoneElsesSubscriptionIsNotFound() {
        when(subscriptions.findById(1L)).thenReturn(Optional.of(saved(1, "ACTIVE", NOW)));

        assertThrows(OrderNotFoundException.class, () -> service.cancel(1, PHNO + 1));
        assertThrows(OrderNotFoundException.class, () -> service.pause(1, PHNO + 1));
    }

    // ---------- runDue ----------

    @Test
    void aDueSubscriptionPlacesACashOrderAndMovesToTheNextInterval() {
        Subscription s = saved(4, "ACTIVE", NOW.minus(Duration.ofMinutes(5)));
        when(subscriptions.findByStatusAndNextRunAtLessThanEqualOrderByIdAsc("ACTIVE", NOW)).thenReturn(List.of(s));
        Cart placed = new Cart();
        placed.setOrderId(77L);
        when(orderService.placeSubscriptionOrder(any(Cart.class), anyLong(), anyDouble())).thenReturn(placed);

        var result = service.runDue();

        assertEquals(1, result.placed());
        ArgumentCaptor<Cart> cart = ArgumentCaptor.forClass(Cart.class);
        verify(orderService).placeSubscriptionOrder(cart.capture(), org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq(5.0));
        assertEquals(PaymentMethod.CASH, cart.getValue().getPaymentMethod());
        assertEquals(PHNO, cart.getValue().getCustomerPhno());
        assertEquals(1, cart.getValue().getOrderItems().get(0).getProductId());
        assertEquals(2, cart.getValue().getOrderItems().get(0).getProductQuantity());
        assertEquals("Subscription #4", cart.getValue().getDeliveryNote());
        assertEquals(77L, s.getLastOrderId());
        assertEquals(1, s.getOrdersPlaced());
        assertEquals(NOW.plus(Duration.ofDays(30)), s.getNextRunAt());
    }

    @Test
    void aFailedOrderIsRetriedTomorrowAndThreeInARowPauseTheSubscription() {
        Subscription s = saved(4, "ACTIVE", NOW);
        when(subscriptions.findByStatusAndNextRunAtLessThanEqualOrderByIdAsc("ACTIVE", NOW)).thenReturn(List.of(s));
        when(orderService.placeSubscriptionOrder(any(Cart.class), anyLong(), anyDouble())).thenThrow(new ProductException("Product quantity exceeded"));

        var first = service.runDue();
        assertEquals(1, first.failed());
        assertEquals("ACTIVE", s.getStatus());
        assertEquals(1, s.getConsecutiveFailures());
        assertEquals("Product quantity exceeded", s.getLastError());
        assertEquals(NOW.plus(Duration.ofDays(1)), s.getNextRunAt());

        s.setNextRunAt(NOW);
        service.runDue();
        s.setNextRunAt(NOW);
        var third = service.runDue();

        assertEquals(1, third.paused());
        assertEquals("PAUSED", s.getStatus());
        assertEquals(3, s.getConsecutiveFailures());
    }

    @Test
    void aSuccessfulOrderClearsEarlierFailures() {
        Subscription s = saved(4, "ACTIVE", NOW);
        s.setConsecutiveFailures(2);
        s.setLastError("old");
        when(subscriptions.findByStatusAndNextRunAtLessThanEqualOrderByIdAsc("ACTIVE", NOW)).thenReturn(List.of(s));
        Cart placed = new Cart();
        placed.setOrderId(5L);
        when(orderService.placeSubscriptionOrder(any(Cart.class), anyLong(), anyDouble())).thenReturn(placed);

        service.runDue();

        assertEquals(0, s.getConsecutiveFailures());
        assertNull(s.getLastError());
        assertTrue(s.getOrdersPlaced() == 1);
    }
}
