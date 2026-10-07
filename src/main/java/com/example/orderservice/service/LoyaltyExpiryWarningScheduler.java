package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs LoyaltyExpiryWarningService once a day (loyalty.warning.cron, default 09:30 in loyalty.warning.zone). Safe to
 * run any time and to miss - the next run catches whoever is still inside the warning window. Off with
 * loyalty.warning.enabled=false (the test profile does).
 */
@Component
@ConditionalOnProperty(name = "loyalty.warning.enabled", havingValue = "true", matchIfMissing = true)
public class LoyaltyExpiryWarningScheduler {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyExpiryWarningScheduler.class);

    private final LoyaltyExpiryWarningService service;

    public LoyaltyExpiryWarningScheduler(LoyaltyExpiryWarningService service) {
        this.service = service;
    }

    @Scheduled(cron = "${loyalty.warning.cron:0 30 9 * * *}", zone = "${loyalty.warning.zone:Asia/Kolkata}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("Loyalty expiry warning run failed: {}", e.getMessage(), e);
        }
    }
}
