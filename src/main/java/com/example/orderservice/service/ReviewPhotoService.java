package com.example.orderservice.service;

import com.example.orderservice.exception.ProductException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Photos customers attach to reviews. Each upload is checked by its actual bytes (JPEG or PNG only, never the declared
 * content type), decoded and written out again as a fresh file. Re-encoding drops everything the camera embedded -
 * above all GPS location - and anything that is not really an image; the camera's rotation is applied first so a
 * portrait photo is not left sideways once that metadata is gone. Pictures are scaled down to at most
 * {@value #MAX_SIDE} px on the long side. Files get a random name (the only thing ever used in a path) and are served
 * back at /review-photos/**.
 * <p>
 * A review may only point at a photo uploaded here ({@link #requireOwnUpload}), never at an arbitrary web address, so
 * the storefront cannot be used to load images from, or show someone else's pictures served by, a third-party site.
 * Uploads are limited per customer per hour so the disk cannot be filled from one account.
 */
@Service
public class ReviewPhotoService {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000L;
    static final int MAX_SIDE = 1600;
    static final int MAX_UPLOADS_PER_HOUR = 10;
    private static final Duration WINDOW = Duration.ofHours(1);
    // The path of an issued photo; the host is whatever this service was reached on, so it is not compared.
    private static final Pattern ISSUED_PATH = Pattern.compile("/review-photos/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png))");

    private final Path directory;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> recentUploads = new HashMap<>();

    public ReviewPhotoService(@Value("${app.review-photo-dir:review-photos}") String directory, Clock clock) {
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** Validates, cleans and stores one photo and returns its file name (e.g. "3f2c....jpg"). */
    public String store(long phno, byte[] bytes) {
        validatePhno(phno);
        if (bytes == null || bytes.length == 0) {
            throw new ProductException("No photo was uploaded");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ProductException("The photo is too big - the limit is 2 MB");
        }
        boolean png = isPng(bytes);
        if (!png && !isJpeg(bytes)) {
            throw new ProductException("Only JPEG or PNG photos are allowed");
        }
        takeUploadSlot(phno);

        byte[] clean = reencode(bytes, png);
        String name = UUID.randomUUID() + (png ? ".png" : ".jpg");
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), clean);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the photo", e);
        }
        return name;
    }

    /**
     * The URL back if it is a photo this service issued and that still exists, null for a blank one; anything else
     * (another site, a made-up name, a path trick) is rejected.
     */
    public String requireOwnUpload(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return null;
        }
        String url = photoUrl.trim();
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            throw new ProductException("That photo is not one you uploaded");
        }
        Matcher m = path == null ? null : ISSUED_PATH.matcher(path);
        if (m == null || !m.matches() || !Files.isRegularFile(directory.resolve(m.group(1)))) {
            throw new ProductException("That photo is not one you uploaded - attach it with the photo button");
        }
        return url;
    }

    // ---------- limits ----------

    private synchronized void takeUploadSlot(long phno) {
        Instant now = clock.instant();
        Deque<Instant> times = recentUploads.computeIfAbsent(phno, k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) {
            times.pollFirst();
        }
        if (times.size() >= MAX_UPLOADS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many photo uploads - try again later");
        }
        times.addLast(now);
    }

    // ---------- image handling ----------

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if (b[i] != sig[i]) return false;
        return true;
    }

    private static byte[] reencode(byte[] original, boolean png) {
        BufferedImage image = decode(original);
        int orientation = png ? 1 : exifOrientation(original);
        image = applyOrientation(image, orientation);
        image = scaleDown(image);
        try {
            return png ? writePng(image) : writeJpeg(image);
        } catch (IOException e) {
            throw new ProductException("That file could not be read as a photo");
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ProductException("That file could not be read as a photo");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // Refuse a decompression bomb before allocating the pixels.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new ProductException("That photo has too many pixels - please use a smaller one");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ProductException pe) {
                throw pe;
            }
            throw new ProductException("That file could not be read as a photo");
        }
    }

    // EXIF orientation (1-8) from a JPEG's APP1 segment, 1 (no change) when absent or unreadable.
    static int exifOrientation(byte[] jpeg) {
        try {
            int i = 2;
            while (i + 4 <= jpeg.length) {
                if ((jpeg[i] & 0xFF) != 0xFF) return 1;
                int marker = jpeg[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) return 1; // image data starts: no EXIF before it
                int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
                if (marker == 0xE1 && length >= 16 && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x' && jpeg[i + 6] == 'i'
                        && jpeg[i + 7] == 'f' && jpeg[i + 8] == 0 && jpeg[i + 9] == 0) {
                    return tiffOrientation(jpeg, i + 10, i + 2 + length);
                }
                i += 2 + length;
            }
        } catch (RuntimeException e) {
            // Malformed EXIF just means no rotation.
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int tiff, int end) {
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') little = true;
        else if (b[tiff] == 'M' && b[tiff + 1] == 'M') little = false;
        else return 1;
        int ifd = tiff + (int) readInt(b, tiff + 4, 4, little);
        if (ifd < tiff || ifd + 2 > end) return 1;
        int entries = (int) readInt(b, ifd, 2, little);
        for (int e = 0; e < entries; e++) {
            int at = ifd + 2 + e * 12;
            if (at + 12 > end) return 1;
            if (readInt(b, at, 2, little) == 0x0112) {
                int value = (int) readInt(b, at + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static long readInt(byte[] b, int at, int size, boolean little) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            int byteValue = b[at + (little ? size - 1 - i : i)] & 0xFF;
            v = (v << 8) | byteValue;
        }
        return v;
    }

    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.translate(h, 0); t.rotate(Math.PI / 2); t.scale(-1, 1); t.translate(-w, 0); }
            default -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage src) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= MAX_SIDE) {
            return src;
        }
        double factor = (double) MAX_SIDE / longest;
        int w = Math.max(1, (int) Math.round(src.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(src.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        // JPEG has no transparency: flatten onto white.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
