package com.example.orderservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Authenticator-app codes (TOTP, RFC 6238: HMAC-SHA1, 30 second steps, 6 digits) for admin two-step sign-in, plus keeping the
 * shared secret encrypted in the database (AES-GCM; the key is derived from the shop's service key, so a copy of the table
 * alone cannot be used to make codes).
 */
@Service
public class TotpService {
    static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecretKeySpec encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public TotpService(@Value("${internal.service.api-key}") String serviceKey) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256").digest(("admin-totp:" + serviceKey).getBytes(StandardCharsets.UTF_8));
            this.encryptionKey = new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh 160-bit secret, base32 (what authenticator apps take). */
    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    public String otpauthUri(String username, String secret, String issuer) {
        String label = URLEncoder.encode(issuer + ":" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    public long stepAt(Instant at) {
        return at.getEpochSecond() / STEP_SECONDS;
    }

    /** The code for one time step. */
    public String codeForStep(String secret, long step) {
        try {
            byte[] key = unbase32(secret);
            byte[] counter = new byte[8];
            for (int i = 7, s = 0; i >= 0; i--, s += 8) {
                counter[i] = (byte) (step >>> s);
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The time step the code belongs to (this one, or one either side to allow for clock drift), or -1 when it matches none.
     * The caller refuses a step it has already accepted, so one code cannot be used twice.
     */
    public long matchingStep(String secret, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return -1;
        }
        long current = stepAt(now);
        long match = -1;
        for (long step = current - 1; step <= current + 1; step++) {
            // compare every candidate (no early exit) so timing says nothing about which one matched
            boolean same = MessageDigest.isEqual(codeForStep(secret, step).getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
            if (same) {
                match = step;
            }
        }
        return match;
    }

    public String encrypt(String secret) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, in, 0, 12));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The stored two-step secret could not be read (was the service key changed?)", e);
        }
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
