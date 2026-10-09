package com.example.orderservice.service;

import com.example.orderservice.exception.ProductException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPhotoServiceTest {
    private static final long PHNO = 9876543210L;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path dir;

    private MutableClock clock;
    private ReviewPhotoService service;

    // A clock the test can move forward, for the hourly upload limit.
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-09T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        clock = new MutableClock();
        Path sub = Files.createTempDirectory(dir, "photos");
        service = new ReviewPhotoService(sub.toString(), clock);
    }

    private Path photoDir() {
        // The service's own folder: the only directory under the temp root that was created for it.
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage solid(int w, int h, int type) {
        BufferedImage image = new BufferedImage(w, h, type);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private BufferedImage storedImage(String name) throws IOException {
        return ImageIO.read(photoDir().resolve(name).toFile());
    }

    // A JPEG with an EXIF APP1 right after SOI: the given orientation, plus a recognisable "GPS" string that must not
    // survive re-encoding.
    private static byte[] jpegWithExif(BufferedImage image, int orientation, String secret) throws IOException {
        byte[] plain = encode(image, "jpg");
        byte[] secretBytes = secret.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[]{'M', 'M', 0, 0x2A, 0, 0, 0, 8});          // big-endian TIFF header, IFD0 at offset 8
        tiff.write(new byte[]{0, 1});                                      // one entry
        tiff.write(new byte[]{0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0}); // Orientation, SHORT, value
        tiff.write(new byte[]{0, 0, 0, 0});                                // no next IFD
        tiff.write(secretBytes);
        byte[] payload = tiff.toByteArray();
        int length = 2 + 6 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                                            // SOI
        out.write(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.write(payload);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    @Test
    void storesAJpegAndAPngAsFreshImageFilesWithRandomNames() throws IOException {
        String jpg = service.store(PHNO, encode(solid(100, 50, BufferedImage.TYPE_INT_RGB), "jpg"));
        String png = service.store(PHNO, encode(solid(60, 80, BufferedImage.TYPE_INT_ARGB), "png"));

        assertTrue(jpg.matches("[0-9a-f-]{36}\\.jpg"));
        assertTrue(png.matches("[0-9a-f-]{36}\\.png"));
        assertEquals(100, storedImage(jpg).getWidth());
        assertEquals(80, storedImage(png).getHeight());
    }

    @Test
    void rejectsAnythingThatIsNotReallyAJpegOrPngWhateverItIsCalled() throws IOException {
        byte[] gif = encode(solid(10, 10, BufferedImage.TYPE_INT_RGB), "gif");
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8};

        for (byte[] bad : new byte[][]{gif, html, svg, fakeJpeg}) {
            assertThrows(ProductException.class, () -> service.store(PHNO, bad));
        }
        assertThrows(ProductException.class, () -> service.store(PHNO, new byte[0]));
        assertThrows(ProductException.class, () -> service.store(PHNO, null));
        assertEquals(0, photoDir().toFile().list().length);
    }

    @Test
    void rejectsAPhotoOverTwoMegabytesAndAnInvalidPhone() throws IOException {
        byte[] big = new byte[(int) ReviewPhotoService.MAX_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThrows(ProductException.class, () -> service.store(PHNO, big));
        assertThrows(ProductException.class, () -> service.store(123L, encode(solid(5, 5, BufferedImage.TYPE_INT_RGB), "jpg")));
    }

    @Test
    void rejectsAPhotoWithTooManyPixelsWithoutDecodingIt() throws IOException {
        // 7000 x 7000 = 49M pixels, but as a 1-bit PNG it is only a few KB.
        byte[] bomb = encode(new BufferedImage(7000, 7000, BufferedImage.TYPE_BYTE_BINARY), "png");
        assertTrue(bomb.length < ReviewPhotoService.MAX_BYTES);
        assertThrows(ProductException.class, () -> service.store(PHNO, bomb));
    }

    @Test
    void reencodingDropsEmbeddedMetadataSuchAsGps() throws IOException {
        byte[] original = jpegWithExif(solid(40, 20, BufferedImage.TYPE_INT_RGB), 1, "GPSLATITUDE-12.97-GPSLONGITUDE-77.59");
        assertTrue(contains(original, "GPSLATITUDE"));

        String name = service.store(PHNO, original);
        byte[] stored = Files.readAllBytes(photoDir().resolve(name));

        assertFalse(contains(stored, "GPSLATITUDE"));
        assertFalse(contains(stored, "Exif"));
        assertEquals(40, ImageIO.read(new ByteArrayInputStream(stored)).getWidth());
    }

    @Test
    void theCamerasRotationIsAppliedBeforeTheMetadataIsDropped() throws IOException {
        // Orientation 6 = the picture is stored sideways and must be turned 90 degrees clockwise: 40x20 becomes 20x40.
        String name = service.store(PHNO, jpegWithExif(solid(40, 20, BufferedImage.TYPE_INT_RGB), 6, "x"));

        BufferedImage stored = storedImage(name);
        assertEquals(20, stored.getWidth());
        assertEquals(40, stored.getHeight());
    }

    @Test
    void readsTheExifOrientationFromBothByteOrdersAndIgnoresGarbage() throws IOException {
        BufferedImage image = solid(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int o = 1; o <= 8; o++) {
            assertEquals(o, ReviewPhotoService.exifOrientation(jpegWithExif(image, o, "x")));
        }
        assertEquals(1, ReviewPhotoService.exifOrientation(encode(image, "jpg")));
        assertEquals(1, ReviewPhotoService.exifOrientation(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 4}));
        byte[] outOfRange = jpegWithExif(image, 9, "x");
        assertEquals(1, ReviewPhotoService.exifOrientation(outOfRange));
    }

    @Test
    void everyOrientationMovesEachPixelWhereTheExifSpecSays() {
        int w = 3;
        int h = 2;
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                src.setRGB(x, y, 0xFF000000 | (x * 60 << 16) | (y * 100 << 8) | 0x11);
            }
        }
        // For each orientation: where does source pixel (x, y) end up? (the standard definitions)
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage out = ReviewPhotoService.applyOrientation(src, orientation);
            boolean swapped = orientation >= 5;
            assertEquals(swapped ? h : w, out.getWidth(), "width for " + orientation);
            assertEquals(swapped ? w : h, out.getHeight(), "height for " + orientation);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int[] to = switch (orientation) {
                        case 1 -> new int[]{x, y};
                        case 2 -> new int[]{w - 1 - x, y};
                        case 3 -> new int[]{w - 1 - x, h - 1 - y};
                        case 4 -> new int[]{x, h - 1 - y};
                        case 5 -> new int[]{y, x};
                        case 6 -> new int[]{h - 1 - y, x};
                        case 7 -> new int[]{h - 1 - y, w - 1 - x};
                        default -> new int[]{y, w - 1 - x};
                    };
                    assertEquals(src.getRGB(x, y), out.getRGB(to[0], to[1]), "orientation " + orientation + " pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void largePicturesAreScaledDownToTheLongSideLimit() throws IOException {
        String name = service.store(PHNO, encode(solid(3200, 1000, BufferedImage.TYPE_INT_RGB), "jpg"));

        BufferedImage stored = storedImage(name);
        assertEquals(1600, stored.getWidth());
        assertEquals(500, stored.getHeight());
    }

    @Test
    void anAccountMayUploadTenPhotosAnHourThenWaits() throws IOException {
        byte[] photo = encode(solid(10, 10, BufferedImage.TYPE_INT_RGB), "jpg");
        for (int i = 0; i < ReviewPhotoService.MAX_UPLOADS_PER_HOUR; i++) {
            service.store(PHNO, photo);
        }

        ResponseStatusException tooMany = assertThrows(ResponseStatusException.class, () -> service.store(PHNO, photo));
        assertEquals(429, tooMany.getStatusCode().value());
        service.store(PHNO + 1, photo); // someone else is unaffected

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        service.store(PHNO, photo);
    }

    @Test
    void aFailedUploadDoesNotUseUpTheHourlyAllowance() throws IOException {
        for (int i = 0; i < 30; i++) {
            assertThrows(ProductException.class, () -> service.store(PHNO, "not an image".getBytes(StandardCharsets.UTF_8)));
        }
        service.store(PHNO, encode(solid(10, 10, BufferedImage.TYPE_INT_RGB), "jpg"));
    }

    @Test
    void aReviewMayOnlyPointAtAPhotoThisServiceIssuedAndStillHas() throws IOException {
        String name = service.store(PHNO, encode(solid(10, 10, BufferedImage.TYPE_INT_RGB), "jpg"));
        String url = "http://localhost:8083/review-photos/" + name;

        assertEquals(url, service.requireOwnUpload("  " + url + " "));
        assertEquals("https://shop.example.com/review-photos/" + name, service.requireOwnUpload("https://shop.example.com/review-photos/" + name));
        assertNull(service.requireOwnUpload(null));
        assertNull(service.requireOwnUpload("   "));

        for (String bad : new String[]{
                "https://evil.example/pixel.png",                                      // someone else's picture
                "http://localhost:8083/review-photos/" + name.replace(".jpg", ".gif"),  // wrong type
                "http://localhost:8083/review-photos/../application.properties",
                "http://localhost:8083/review-photos/" + "0".repeat(8) + "-0000-0000-0000-" + "0".repeat(12) + ".jpg", // never issued
                "http://localhost:8083/uploads/" + name,
                "javascript:alert(1)",
                "http://localhost:8083/review-photos/" + name + "/../../x"}) {
            assertThrows(ProductException.class, () -> service.requireOwnUpload(bad), bad);
        }
    }
}
