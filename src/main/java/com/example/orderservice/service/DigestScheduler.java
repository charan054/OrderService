package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the admin digest once a day (digest.cron, default 08:00 in digest.zone - a Spring cron: sec min hour day
 * month weekday). Does nothing until digest.to (ADMIN_EMAIL) is set. Off with digest.enabled=false (the test
 * profile does). A cron fires at a wall-clock time, so a restart can skip that day's digest - acceptable for a
 * summary email, and POST /cart/digest/send covers the gap.
 */
@Component
@ConditionalOnProperty(name = "digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestScheduler {
    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    private final DigestService digestService;

    public DigestScheduler(DigestService digestService) {
        this.digestService = digestService;
    }

    @Scheduled(cron = "${digest.cron:0 0 8 * * *}", zone = "${digest.zone:Asia/Kolkata}")
    public void run() {
        try {
            if (digestService.isConfigured()) {
                digestService.send();
            }
        } catch (RuntimeException e) {
            // Never kill the scheduler thread - tomorrow's run just tries again.
            log.error("Daily digest failed: {}", e.getMessage(), e);
        }
    }
}
