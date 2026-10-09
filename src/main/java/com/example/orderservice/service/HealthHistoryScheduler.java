package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Takes a health sample every health.history.interval-ms (default 5 minutes) so the admin dashboard can show uptime.
 * OFF unless health.history.enabled=true (HEALTH_HISTORY_ENABLED), like the other background jobs that talk to other
 * services: probing every few minutes forever is something to opt into. fixedDelay so a slow probe never overlaps
 * the next one.
 */
@Component
@ConditionalOnProperty(name = "health.history.enabled", havingValue = "true")
public class HealthHistoryScheduler {
    private static final Logger log = LoggerFactory.getLogger(HealthHistoryScheduler.class);

    private final HealthHistoryService history;

    public HealthHistoryScheduler(HealthHistoryService history) {
        this.history = history;
    }

    @Scheduled(initialDelayString = "${health.history.initial-delay-ms:60000}", fixedDelayString = "${health.history.interval-ms:300000}")
    public void run() {
        try {
            history.record();
        } catch (RuntimeException e) {
            // Never kill the scheduler thread - the next tick just tries again.
            log.error("Health sample failed: {}", e.getMessage(), e);
        }
    }
}
