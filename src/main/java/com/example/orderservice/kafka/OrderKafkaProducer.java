package com.example.orderservice.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderKafkaProducer {
    private static final Logger log = LoggerFactory.getLogger(OrderKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderKafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendMessage(String message) {
        kafkaTemplate.send(KafkaTopics.ORDER_NOTIFICATION_TOPIC, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka send failed: {}", ex.getMessage());
                    } else {
                        log.info("Kafka message sent successfully {}", message);
                    }
                });
    }
}
