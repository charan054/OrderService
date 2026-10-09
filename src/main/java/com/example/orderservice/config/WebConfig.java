package com.example.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

// Serves the review photos POST /cart/reviews/photo has written to app.review-photo-dir back out at /review-photos/**.
// A plain filesystem directory (not a classpath resource) so a photo survives a rebuild; file names are random and
// never change, so browsers may cache them for a while.
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String reviewPhotoDir;

    public WebConfig(@Value("${app.review-photo-dir:review-photos}") String reviewPhotoDir) {
        this.reviewPhotoDir = reviewPhotoDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A directory that does not exist yet (nothing uploaded so far) has no trailing slash in its URI, which Spring
        // would warn about and patch up at every start - give it one up front.
        String location = Paths.get(reviewPhotoDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/review-photos/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
    }
}
