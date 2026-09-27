package com.example.orderservice.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // KafkaProducer.send() blocks the calling thread (up to this long) while it fetches topic metadata, even
        // though the CompletableFuture it returns looks "fire and forget" - the default 60s meant every order
        // placement/cancellation/shipment/etc. silently hung for a minute whenever no broker is reachable (which
        // it never is in this dev environment) before sendNotification()'s try/catch could even log the failure.
        // Confirmed live: a real PhonePe checkout took over 60s to respond until this was set.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
