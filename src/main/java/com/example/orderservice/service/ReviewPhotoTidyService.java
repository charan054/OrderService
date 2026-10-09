package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PhotoTidyResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Removes review-photo files no review refers to any more (the review was deleted, or the customer uploaded a photo
 * and never posted it). Which photos are in use comes from ProductService, which owns the reviews; if it cannot be
 * reached nothing is deleted. A photo younger than review-photo.tidy.min-age-minutes is always kept (never below an
 * hour) because it may be waiting to be attached to a review that is being written right now.
 */
@Service
public class ReviewPhotoTidyService {
    static final long MIN_AGE_FLOOR_MINUTES = 60;

    private final ReviewPhotoService photos;
    private final ProductClient productClient;
    private final String serviceKey;
    private final Duration minAge;

    public ReviewPhotoTidyService(ReviewPhotoService photos, ProductClient productClient,
                                  @Value("${internal.service.api-key}") String serviceKey,
                                  @Value("${review-photo.tidy.min-age-minutes:60}") long minAgeMinutes) {
        this.photos = photos;
        this.productClient = productClient;
        this.serviceKey = serviceKey;
        this.minAge = Duration.ofMinutes(Math.max(MIN_AGE_FLOOR_MINUTES, minAgeMinutes));
    }

    public PhotoTidyResult tidy(boolean dryRun) {
        try {
            return photos.tidy(() -> productClient.getReviewPhotoUrls(serviceKey), dryRun, minAge);
        } catch (RuntimeException e) {
            // Nothing is deleted before the reference list is in hand, so any failure here means nothing was.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read the list of photos in use from ProductService - nothing was deleted");
        }
    }
}
