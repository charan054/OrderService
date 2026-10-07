package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically runs StockAlertService. fixedDelay (not fixedRate) so a slow run - many customers, a slow mail
 * server - can never overlap the next one. Switch off with alerts.enabled=false (the test profile does).
 */
@Component
@ConditionalOnProperty(name = "alerts.enabled", havingValue = "true", matchIfMissing = true)
public class StockAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(StockAlertScheduler.class);

    private final StockAlertService stockAlertService;

    public StockAlertScheduler(StockAlertService stockAlertService) {
        this.stockAlertService = stockAlertService;
    }

    @Scheduled(initialDelayString = "${alerts.initial-delay-ms:60000}", fixedDelayString = "${alerts.interval-ms:600000}")
    public void run() {
        try {
            stockAlertService.run();
        } catch (RuntimeException e) {
            // A failed run must never kill the scheduler thread - the next tick just tries again.
            log.error("Stock alert run failed: {}", e.getMessage(), e);
        }
    }
}
