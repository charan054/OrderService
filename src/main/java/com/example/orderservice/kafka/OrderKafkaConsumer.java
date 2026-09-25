package com.example.orderservice.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

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
