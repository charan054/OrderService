package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Places subscription orders that have fallen due, every subscription.interval-ms (default hourly). OFF unless
 * subscription.enabled=true: it creates real orders for customers, so it should only run once someone has decided to.
 */
@Component
@ConditionalOnProperty(name = "subscription.enabled", havingValue = "true")
public class SubscriptionScheduler {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    private final SubscriptionService service;

    public SubscriptionScheduler(SubscriptionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${subscription.interval-ms:3600000}", initialDelayString = "${subscription.initial-delay-ms:120000}")
    public void run() {
        try {
            var result = service.runDue();
            if (result.placed() + result.failed() > 0) {
                log.info("Subscriptions: {} order(s) placed, {} failed, {} paused", result.placed(), result.failed(), result.paused());
            }
        } catch (RuntimeException e) {
            log.error("Subscription run failed: {}", e.getMessage(), e);
        }
    }
}
