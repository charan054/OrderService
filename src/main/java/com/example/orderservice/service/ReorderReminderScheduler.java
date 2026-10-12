package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs ReorderReminderService once a day (reorder.reminder.cron, default 10:00 in reorder.reminder.zone). Off unless
 * reorder.reminder.enabled=true (REORDER_REMINDERS_ENABLED) - these are marketing emails, so they are opt-in for the
 * store. Safe to miss: each purchase is nudged once, whenever the next run happens.
 */
@Component
@ConditionalOnProperty(name = "reorder.reminder.enabled", havingValue = "true")
public class ReorderReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReorderReminderScheduler.class);

    private final ReorderReminderService service;

    public ReorderReminderScheduler(ReorderReminderService service) {
        this.service = service;
    }

    @Scheduled(cron = "${reorder.reminder.cron:0 0 10 * * *}", zone = "${reorder.reminder.zone:Asia/Kolkata}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("Reorder reminder run failed: {}", e.getMessage(), e);
        }
    }
}
