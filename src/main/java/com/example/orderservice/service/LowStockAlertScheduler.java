package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically runs LowStockAlertService. Off with lowstock.alert.enabled=false (the test profile does). */
@Component
@ConditionalOnProperty(name = "lowstock.alert.enabled", havingValue = "true", matchIfMissing = true)
public class LowStockAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(LowStockAlertScheduler.class);

    private final LowStockAlertService service;

    public LowStockAlertScheduler(LowStockAlertService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${lowstock.alert.initial-delay-ms:120000}",
            fixedDelayString = "${lowstock.alert.interval-ms:1800000}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            // ProductService being down must not kill the scheduler thread - the next tick tries again.
            log.error("Low-stock alert run failed: {}", e.getMessage(), e);
        }
    }
}
