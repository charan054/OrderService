package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs AbandonedCartService hourly (abandoned-cart.cron). OFF unless abandoned-cart.enabled=true: unlike the
 * internal digest this emails customers, so it should only start once real mail credentials are configured and
 * someone has decided they want it.
 */
@Component
@ConditionalOnProperty(name = "abandoned-cart.enabled", havingValue = "true")
public class AbandonedCartScheduler {
    private static final Logger log = LoggerFactory.getLogger(AbandonedCartScheduler.class);

    private final AbandonedCartService service;

    public AbandonedCartScheduler(AbandonedCartService service) {
        this.service = service;
    }

    @Scheduled(cron = "${abandoned-cart.cron:0 15 * * * *}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("Abandoned cart run failed: {}", e.getMessage(), e);
        }
    }
}
