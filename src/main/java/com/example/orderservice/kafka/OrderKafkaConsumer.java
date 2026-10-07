package com.example.orderservice.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

// Log-only. The customer-facing SHIPPED/DELIVERED notification (email + NotificationLog row) used to be produced
// here, which meant it silently never happened whenever no Kafka broker was reachable - it now comes straight
// from OrderService.ship()/deliver() via CustomerNotifier. Recording it here as well would double it up whenever
// a broker IS running.
@Service
public class OrderKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderKafkaConsumer.class);

    @KafkaListener(
            topics = KafkaTopics.ORDER_NOTIFICATION_TOPIC,
            groupId = "order-notification-group"
    )
    public void consume(String message) {
        log.info("Kafka message received: {}", message);
    }
}
