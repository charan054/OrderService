package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.DeliveryAttempt;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.DeliveryAttemptRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryProblemServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private TrackingEventRepository tracking;
    private DeliveryAttemptRepository attempts;
    private CartRepository orders;
    private ShippingAddressRepository addresses;
    private DeliveryProblemService service;

    @BeforeEach
    void setUp() {
        tracking = mock(TrackingEventRepository.class);
        attempts = mock(DeliveryAttemptRepository.class);
        orders = mock(CartRepository.class);
        addresses = mock(ShippingAddressRepository.class);
        service = new DeliveryProblemService(tracking, attempts, orders, addresses, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrackingEvent shipped(long orderId) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(OrderStatus.SHIPPED);
        return e;
    }

    private static DeliveryAttempt failed(long orderId, String reason) {
        DeliveryAttempt a = new DeliveryAttempt();
        a.setOrderId(orderId);
        a.setReason(reason);
        return a;
    }

    private static Cart order(long id, Long addressId, String delayReason) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setShippingAddressId(addressId);
        c.setDelayReason(delayReason);
        return c;
    }

    private static ShippingAddress address(long id, String pincode, String city) {
        ShippingAddress a = new ShippingAddress();
        a.setId(id);
        a.setPincode(pincode);
        a.setCity(city);
        return a;
    }

    @Test
    void groupsShippedFailedAndDelayedOrdersByPincodeWorstFirst() {
        when(tracking.findByStatusAndTimestampGreaterThanEqual(eq(OrderStatus.SHIPPED), any())).thenReturn(List.of(shipped(1), shipped(2), shipped(3), shipped(4)));
        when(attempts.findByCreatedAtGreaterThanEqual(any())).thenReturn(List.of(failed(1, "CUSTOMER_UNAVAILABLE"), failed(1, "REFUSED"), failed(3, "ADDRESS_ISSUE")));
        when(orders.findAllById(anyIterable())).thenReturn(List.of(order(1, 10L, null), order(2, 10L, "Rain"), order(3, 11L, null), order(4, null, null)));
        when(addresses.findAllById(anyIterable())).thenReturn(List.of(address(10, "500001", "Hyderabad"), address(11, "110001", "Delhi")));

        DeliveryProblemService.Report report = service.report(30);

        assertEquals(4, report.shipped());
        assertEquals(3, report.problemOrders());
        assertEquals(List.of("500001", "110001", "unknown"), report.areas().stream().map(DeliveryProblemService.Area::pincode).toList());
        DeliveryProblemService.Area hyderabad = report.areas().get(0);
        assertEquals("Hyderabad", hyderabad.city());
        assertEquals(2, hyderabad.shipped());
        assertEquals(1, hyderabad.failedOrders());
        assertEquals(2, hyderabad.failedAttempts());
        assertEquals(1, hyderabad.delayedOrders());
        assertEquals(100.0, hyderabad.problemPercent());
        assertEquals(1, hyderabad.reasons().get("REFUSED"));
        assertEquals(0.0, report.areas().get(2).problemPercent());
    }

    @Test
    void rejectsAnUnreasonableWindow() {
        assertThrows(ProductException.class, () -> service.report(0));
        assertThrows(ProductException.class, () -> service.report(366));
    }
}
