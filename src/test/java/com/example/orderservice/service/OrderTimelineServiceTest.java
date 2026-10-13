package com.example.orderservice.service;

import com.example.orderservice.dto.TimelineEntry;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderTimelineServiceTest {
    private static final long PHNO = 9000000001L;
    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    private CartRepository orders;
    private TrackingEventRepository tracking;
    private NotificationLogRepository notifications;
    private StoreCreditTransactionRepository credits;
    private com.example.orderservice.repository.DeliveryAttemptRepository deliveryAttempts;
    private com.example.orderservice.repository.ExchangeRequestRepository exchangeRepo;
    private OrderTimelineService service;
    private Cart order;

    @BeforeEach
    void setUp() {
        orders = mock(CartRepository.class);
        tracking = mock(TrackingEventRepository.class);
        notifications = mock(NotificationLogRepository.class);
        credits = mock(StoreCreditTransactionRepository.class);
        deliveryAttempts = mock(com.example.orderservice.repository.DeliveryAttemptRepository.class);
        exchangeRepo = mock(com.example.orderservice.repository.ExchangeRequestRepository.class);
        service = new OrderTimelineService(orders, tracking, notifications, credits, deliveryAttempts, exchangeRepo);
        order = new Cart();
        order.setOrderId(42L);
        order.setCustomerPhno(PHNO);
        order.setStatus(OrderStatus.RETURNED);
        order.setCarrier("Delhivery");
        when(orders.findById(42L)).thenReturn(Optional.of(order));
        when(tracking.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of());
        when(notifications.findByOrderIdOrderBySentAtAsc(42L)).thenReturn(List.of());
        when(credits.findByOrderIdAndTypeOrderByIdAsc(42L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of());
    }

    private TrackingEvent event(OrderStatus status, long minutes) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(42L);
        e.setStatus(status);
        e.setTimestamp(T0.plusSeconds(minutes * 60));
        return e;
    }

    private NotificationLog note(OrderStatus type, String message, long minutes) {
        NotificationLog n = new NotificationLog();
        n.setOrderId(42L);
        n.setEventType(type);
        n.setMessage(message);
        n.setSentAt(T0.plusSeconds(minutes * 60));
        return n;
    }

    @Test
    void mergesStatusStepsUpdatesAndRefundsInOrderWithoutDuplicatingStatusMessages() {
        when(tracking.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of(
                event(OrderStatus.PLACED, 0), event(OrderStatus.SHIPPED, 60), event(OrderStatus.DELIVERED, 120), event(OrderStatus.RETURNED, 300)));
        when(notifications.findByOrderIdOrderBySentAtAsc(42L)).thenReturn(List.of(
                note(OrderStatus.PLACED, "Your order #42 is confirmed", 0),                 // same as the tracking step
                note(OrderStatus.DELIVERED, "Your order #42 has shipped", 60),               // wrong type for the text: kept as an update
                note(OrderStatus.DELIVERED, "Pickup booked for your return of order #42", 200),
                note(OrderStatus.DELIVERED, "Your return for order #42 was picked up", 250),
                note(OrderStatus.RETURNED, "Your return for order #42 is complete", 300)));  // same as the tracking step
        StoreCreditTransaction credit = new StoreCreditTransaction();
        credit.setAmount(45);
        credit.setCreatedAt(T0.plusSeconds(301 * 60));
        when(credits.findByOrderIdAndTypeOrderByIdAsc(42L, StoreCreditTransaction.Type.REFUND)).thenReturn(List.of(credit));

        List<TimelineEntry> steps = service.timeline(42L, PHNO);

        assertEquals(List.of("Order placed", "Shipped via Delhivery", "Your order #42 has shipped", "Delivered",
                "Pickup booked for your return of order #42", "Your return for order #42 was picked up", "Return complete",
                "Rs. 45.00 added to your store credit"), steps.stream().map(TimelineEntry::title).toList());
        assertEquals("REFUND", steps.get(steps.size() - 1).kind());
    }

    @Test
    void anotherCustomersOrderLooksLikeItDoesNotExist() {
        assertThrows(OrderNotFoundException.class, () -> service.timeline(42L, PHNO + 1));
    }

    @Test
    void failedDeliveryAttemptsAppearOnceAndTheirEmailIsNotRepeated() {
        order.setStatus(OrderStatus.SHIPPED);
        when(tracking.findByOrderIdOrderByTimestampAsc(42L)).thenReturn(List.of(event(OrderStatus.SHIPPED, 60)));
        when(notifications.findByOrderIdOrderBySentAtAsc(42L)).thenReturn(List.of(
                note(OrderStatus.SHIPPED, "We could not deliver order #42", 100)));
        com.example.orderservice.entity.DeliveryAttempt attempt = new com.example.orderservice.entity.DeliveryAttempt();
        attempt.setAttemptNo(1);
        attempt.setReason("CUSTOMER_UNAVAILABLE");
        attempt.setCreatedAt(T0.plusSeconds(100 * 60));
        when(deliveryAttempts.findByOrderIdOrderByAttemptNoAsc(42L)).thenReturn(List.of(attempt));

        List<TimelineEntry> steps = service.timeline(42L, PHNO);

        assertEquals(List.of("Shipped via Delhivery", "Delivery attempt 1 failed: customer unavailable"),
                steps.stream().map(TimelineEntry::title).toList());
        assertEquals("DELIVERY", steps.get(1).kind());
    }

    @Test
    void anExchangeShowsWhenItWasRequestedAndWhereItStands() {
        order.setStatus(OrderStatus.DELIVERED);
        com.example.orderservice.entity.ExchangeRequest x = new com.example.orderservice.entity.ExchangeRequest();
        x.setProductId(1);
        x.setQuantity(2);
        x.setReplacementProductId(5);
        x.setStatus("APPROVED");
        x.setCreatedAt(T0.plusSeconds(10 * 60));
        x.setUpdatedAt(T0.plusSeconds(20 * 60));
        when(exchangeRepo.findByOrderIdOrderByIdAsc(42L)).thenReturn(List.of(x));

        List<TimelineEntry> steps = service.timeline(42L, PHNO);

        assertEquals(List.of("Exchange requested: 2 x product #1 for product #5", "Exchange approved - replacement on its way"),
                steps.stream().map(TimelineEntry::title).toList());
        assertEquals("EXCHANGE", steps.get(0).kind());
    }

    @Test
    void theEmailsAnExchangeSentAreNotRepeatedNextToItsOwnEntries() {
        order.setStatus(OrderStatus.DELIVERED);
        when(notifications.findByOrderIdOrderBySentAtAsc(42L)).thenReturn(List.of(
                note(OrderStatus.DELIVERED, "Exchange requested for order #42", 10),
                note(OrderStatus.DELIVERED, "Your exchange for order #42 is approved", 20),
                note(OrderStatus.DELIVERED, "Your exchange for order #42 is complete", 30),
                note(OrderStatus.DELIVERED, "Delivery details updated for order #42", 40)));

        List<TimelineEntry> steps = service.timeline(42L, PHNO);

        assertEquals(List.of("Delivery details updated for order #42"), steps.stream().map(TimelineEntry::title).toList());
    }
}
