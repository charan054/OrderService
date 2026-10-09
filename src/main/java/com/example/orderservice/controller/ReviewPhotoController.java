package com.example.orderservice.controller;

import com.example.orderservice.dto.PhotoTidyResult;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.ReviewPhotoService;
import com.example.orderservice.service.ReviewPhotoTidyService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

// A signed-in customer (own phone number only, see CustomerAccess) or the service key uploads one photo and gets back
// the URL to attach to a review. The browser can only talk to this origin, which is why the upload lives here and not
// in ProductService. The files themselves are served, publicly, at /review-photos/** (see WebConfig).
@RestController
@RequestMapping("/cart/reviews/photo")
public class ReviewPhotoController {
    private final ReviewPhotoService photos;
    private final ReviewPhotoTidyService tidyService;

    public ReviewPhotoController(ReviewPhotoService photos, ReviewPhotoTidyService tidyService) {
        this.photos = photos;
        this.tidyService = tidyService;
    }

    @PostMapping
    public Map<String, String> upload(@RequestParam long phno, @RequestParam("file") MultipartFile file) {
        CustomerAccess.requireSelfOrService(phno);
        if (file == null || file.isEmpty()) {
            throw new ProductException("No photo was uploaded");
        }
        if (file.getSize() > ReviewPhotoService.MAX_BYTES) {
            throw new ProductException("The photo is too big - the limit is 2 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ProductException("The photo could not be read");
        }
        String name = photos.store(phno, bytes);
        // Absolute, built from how this request reached us: shop.html renders it straight into an img src.
        String url = ServletUriComponentsBuilder.fromCurrentContextPath().path("/review-photos/").path(name).toUriString();
        return Map.of("url", url);
    }

    // Admin-only (service key; not one of the customer paths in SecurityConfig, so it falls under the default rule).
    // dryRun defaults to true: you must send dryRun=false to actually delete files.
    @PostMapping("/tidy")
    public PhotoTidyResult tidy(@RequestParam(defaultValue = "true") boolean dryRun) {
        return tidyService.tidy(dryRun);
    }
}
