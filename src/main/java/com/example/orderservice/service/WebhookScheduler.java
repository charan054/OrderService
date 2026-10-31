package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically sends the queued webhooks. Off with webhook.enabled=false (the test profile does). */
@Component
@ConditionalOnProperty(name = "webhook.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookScheduler {
    private static final Logger log = LoggerFactory.getLogger(WebhookScheduler.class);

    private final WebhookService service;
    private final ClusterLocks locks;

    public WebhookScheduler(WebhookService service, ClusterLocks locks) {
        this.service = service;
        this.locks = locks;
    }

    @Scheduled(initialDelayString = "${webhook.initial-delay-ms:30000}", fixedDelayString = "${webhook.interval-ms:15000}")
    public void run() {
        // Several instances on one database: only one sends each round, so a delivery is not sent twice at once.
        locks.runExclusively("webhook-dispatch", this::runLocked);
    }

    private void runLocked() {
        try {
            service.dispatchDue();
        } catch (RuntimeException e) {
            log.error("Webhook dispatch failed: {}", e.getMessage(), e);
        }
    }
}
