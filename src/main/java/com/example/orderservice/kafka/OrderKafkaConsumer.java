package com.example.orderservice.kafka;

import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OrderKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderKafkaConsumer.class);
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("OrderId: (\\d+)");

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @KafkaListener(
            topics = KafkaTopics.ORDER_NOTIFICATION_TOPIC,
            groupId = "order-notification-group"
    )
    public void consume(String message) {
        log.info("Kafka message received: {}", message);
        // Only the transitions a customer actually cares to be told about get turned into a dispatched
        // notification - placed/cancelled/returned are already visible to the buyer as the direct result of an
        // action they themselves just took, unlike ship/deliver which happen on the warehouse's own schedule.
        OrderStatus eventType = message.startsWith("Order shipped.") ? OrderStatus.SHIPPED
                : message.startsWith("Order delivered.") ? OrderStatus.DELIVERED
                : null;
        if (eventType == null) {
            return;
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            log.error("Could not extract an order id from notification: {}", message);
            return;
        }
        NotificationLog notification = new NotificationLog();
        notification.setOrderId(Long.parseLong(matcher.group(1)));
        notification.setEventType(eventType);
        notification.setMessage(message);
        notification.setSentAt(Instant.now());
        notificationLogRepository.save(notification);
        // No email/SMS provider is wired up yet - this is where that dispatch would happen. For now the
        // notification is only logged and recorded for GET /cart/{orderId}/notifications to show.
        log.info("Customer notification dispatched for order {} ({})", notification.getOrderId(), eventType);
    }
}
