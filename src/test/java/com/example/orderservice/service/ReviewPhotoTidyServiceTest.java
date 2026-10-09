package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PhotoTidyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewPhotoTidyServiceTest {
    // Under target/ rather than @TempDir: JUnit's cleanup of the system temp folder is refused on this Windows setup.
    private Path dir;

    private ProductClient productClient;
    private ReviewPhotoService photos;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(Path.of("target"));
        dir = Files.createTempDirectory(Path.of("target"), "tidy-test");
        productClient = mock(ProductClient.class);
        photos = new ReviewPhotoService(dir.toString(), Clock.systemUTC());
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    private Path plant(Duration age) throws IOException {
        Path file = dir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(file, new byte[]{1, 2});
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return file;
    }

    @Test
    void asksProductServiceWhatIsInUseWithTheServiceKeyAndKeepsThose() throws IOException {
        Path used = plant(Duration.ofDays(2));
        Path orphan = plant(Duration.ofDays(2));
        when(productClient.getReviewPhotoUrls("key")).thenReturn(List.of("https://x/review-photos/" + used.getFileName()));

        PhotoTidyResult result = new ReviewPhotoTidyService(photos, productClient, "key", 60).tidy(false);

        verify(productClient).getReviewPhotoUrls("key");
        assertEquals(1, result.orphaned());
        assertTrue(Files.exists(used));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void theAgeLimitCanBeRaisedButNeverLoweredBelowAnHour() throws IOException {
        Path halfHour = plant(Duration.ofMinutes(30));
        Path threeHours = plant(Duration.ofHours(3));
        when(productClient.getReviewPhotoUrls("key")).thenReturn(List.of());

        new ReviewPhotoTidyService(photos, productClient, "key", 1).tidy(false);
        assertTrue(Files.exists(halfHour));
        assertFalse(Files.exists(threeHours));

        Path again = plant(Duration.ofHours(3));
        new ReviewPhotoTidyService(photos, productClient, "key", 24 * 60).tidy(false);
        assertTrue(Files.exists(again));
    }

    @Test
    void whenProductServiceCannotBeReachedNothingIsDeletedAndTheCallerIsTold() throws IOException {
        Path orphan = plant(Duration.ofDays(2));
        when(productClient.getReviewPhotoUrls("key")).thenThrow(new RuntimeException("connection refused"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> new ReviewPhotoTidyService(photos, productClient, "key", 60).tidy(false));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertTrue(Files.exists(orphan));
    }
}
