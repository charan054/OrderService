package com.example.orderservice.kafka;

import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Only SHIPPED/DELIVERED are turned into a dispatched (logged + recorded) customer notification - the other
// order-lifecycle messages already published to the same Kafka topic must be left alone.
@ExtendWith(MockitoExtension.class)
class OrderKafkaConsumerTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @InjectMocks
    private OrderKafkaConsumer consumer;

    @Test
    void dispatchesANotificationForAShippedOrder() {
        consumer.consume("Order shipped. OrderId: 42");

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertEquals(42L, captor.getValue().getOrderId());
        assertEquals(OrderStatus.SHIPPED, captor.getValue().getEventType());
    }

    @Test
    void dispatchesANotificationForADeliveredOrder() {
        consumer.consume("Order delivered. OrderId: 7");

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getOrderId());
        assertEquals(OrderStatus.DELIVERED, captor.getValue().getEventType());
    }

    @Test
    void ignoresAnOrderPlacedMessage() {
        consumer.consume("Order placed successfully. OrderId: 1 Customer: XXXXXX3210 Items: 1 Total: 9.99");
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void ignoresAnOrderCancelledMessage() {
        consumer.consume("Order cancelled successfully. OrderId: 1 Customer: XXXXXX3210 Refunded: 9.99");
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void ignoresAnOrderReturnedMessage() {
        consumer.consume("Order returned successfully. OrderId: 1 Customer: XXXXXX3210 Reason: damaged Refunded: 9.99");
        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    void ignoresAShippedMessageWithNoParseableOrderId() {
        consumer.consume("Order shipped. Something went wrong.");
        verify(notificationLogRepository, never()).save(any());
    }
}
