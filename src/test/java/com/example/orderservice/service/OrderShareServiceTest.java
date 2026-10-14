package com.example.orderservice.service;

import com.example.orderservice.dto.SharedOrderStatus;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderShareLink;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderShareLinkRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderShareServiceTest {
    private static final long PHNO = 9000000001L;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Mock
    private OrderShareLinkRepository links;
    @Mock
    private CartRepository orders;
    @Mock
    private TrackingEventRepository tracking;

    private OrderShareService service;

    @BeforeEach
    void setUp() {
        service = new OrderShareService(links, orders, tracking, Clock.fixed(NOW, ZoneOffset.UTC), 30);
    }

    private Cart order(long id, long phno) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(phno);
        c.setStatus(OrderStatus.SHIPPED);
        c.setCarrier("Delhivery");
        c.setTrackingNumber("DL123");
        c.setDeliveryNote("Leave at the gate");
        c.setTotalPrice(499.0);
        return c;
    }

    private OrderShareLink link(String token, long orderId, Instant expires, Instant revoked) {
        OrderShareLink l = new OrderShareLink();
        l.setToken(token);
        l.setOrderId(orderId);
        l.setCustomerPhno(PHNO);
        l.setExpiresAt(expires);
        l.setRevokedAt(revoked);
        return l;
    }

    @Test
    void createMintsAnUnguessableTokenValidForThirtyDays() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO)));
        when(links.findByOrderIdAndRevokedAtIsNull(7L)).thenReturn(List.of());
        when(links.save(any(OrderShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderShareLink created = service.getOrCreate(7, PHNO);

        assertEquals(32, created.getToken().length());
        assertEquals(NOW.plusSeconds(30L * 86400), created.getExpiresAt());
        assertEquals(7, created.getOrderId());
    }

    @Test
    void askingAgainReturnsTheActiveLinkButNotAnExpiredOne() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO)));
        OrderShareLink active = link("a".repeat(32), 7, NOW.plusSeconds(3600), null);
        when(links.findByOrderIdAndRevokedAtIsNull(7L)).thenReturn(List.of(active));

        assertSame(active, service.getOrCreate(7, PHNO));
        verify(links, never()).save(any());

        when(links.findByOrderIdAndRevokedAtIsNull(7L)).thenReturn(List.of(link("b".repeat(32), 7, NOW.minusSeconds(1), null)));
        when(links.save(any(OrderShareLink.class))).thenAnswer(inv -> inv.getArgument(0));
        assertNotEquals("b".repeat(32), service.getOrCreate(7, PHNO).getToken());
    }

    @Test
    void anotherCustomersOrderIsReportedAsNotFound() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, 9111111111L)));

        assertThrows(OrderNotFoundException.class, () -> service.getOrCreate(7, PHNO));
        assertThrows(OrderNotFoundException.class, () -> service.revoke(7, PHNO));
        verify(links, never()).save(any());
    }

    @Test
    void revokeEndsEveryActiveLink() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO)));
        OrderShareLink one = link("a".repeat(32), 7, NOW.plusSeconds(60), null);
        OrderShareLink two = link("b".repeat(32), 7, NOW.plusSeconds(60), null);
        when(links.findByOrderIdAndRevokedAtIsNull(7L)).thenReturn(List.of(one, two));

        assertEquals(2, service.revoke(7, PHNO));
        assertEquals(NOW, one.getRevokedAt());
        assertEquals(NOW, two.getRevokedAt());
    }

    @Test
    void viewShowsStatusAndStepsButNothingPrivate() {
        when(links.findById("t")).thenReturn(Optional.of(link("t", 7, NOW.plusSeconds(60), null)));
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO)));
        TrackingEvent placed = new TrackingEvent();
        placed.setOrderId(7L);
        placed.setStatus(OrderStatus.PLACED);
        placed.setTimestamp(NOW.minusSeconds(3600));
        when(tracking.findByOrderIdOrderByTimestampAsc(7L)).thenReturn(List.of(placed));

        SharedOrderStatus view = service.view("t");

        assertEquals("SHIPPED", view.status());
        assertEquals("Delhivery", view.carrier());
        assertEquals(1, view.timeline().size());
        assertEquals("PLACED", view.timeline().get(0).status());
        String text = view.toString();
        assertFalse(text.contains("gate"), "delivery notes are private");
        assertFalse(text.contains("499"), "prices are private");
        assertFalse(text.contains(String.valueOf(PHNO)), "the phone number is private");
    }

    @Test
    void viewRefusesUnknownRevokedAndExpiredLinks() {
        when(links.findById("gone")).thenReturn(Optional.empty());
        when(links.findById("revoked")).thenReturn(Optional.of(link("revoked", 7, NOW.plusSeconds(60), NOW.minusSeconds(1))));
        when(links.findById("old")).thenReturn(Optional.of(link("old", 7, NOW.minusSeconds(1), null)));

        assertThrows(OrderNotFoundException.class, () -> service.view("gone"));
        assertThrows(OrderNotFoundException.class, () -> service.view("revoked"));
        assertThrows(OrderNotFoundException.class, () -> service.view("old"));
        assertThrows(OrderNotFoundException.class, () -> service.view(null));
        verifyNoInteractions(orders);
    }
}
