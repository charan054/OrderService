package com.example.orderservice.service;

import com.example.orderservice.dto.PhotoTidyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes unreferenced review photos once a day (03:30 by default). OFF unless review-photo.tidy.enabled=true
 * (REVIEW_PHOTO_TIDY_ENABLED): it deletes files, so it is something to opt into after trying a dry run by hand.
 */
@Component
@ConditionalOnProperty(name = "review-photo.tidy.enabled", havingValue = "true")
public class ReviewPhotoTidyScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReviewPhotoTidyScheduler.class);

    private final ReviewPhotoTidyService service;

    public ReviewPhotoTidyScheduler(ReviewPhotoTidyService service) {
        this.service = service;
    }

    @Scheduled(cron = "${review-photo.tidy.cron:0 30 3 * * *}", zone = "${review-photo.tidy.zone:Asia/Kolkata}")
    public void run() {
        try {
            PhotoTidyResult r = service.tidy(false);
            log.info("Review photo tidy: {} photo(s), {} in use, {} too recent, {} removed ({} bytes)",
                    r.total(), r.inUse(), r.tooRecent(), r.orphaned(), r.orphanedBytes());
        } catch (RuntimeException e) {
            // ProductService being down must not kill the scheduler thread - the next day tries again.
            log.error("Review photo tidy failed: {}", e.getMessage(), e);
        }
    }
}
