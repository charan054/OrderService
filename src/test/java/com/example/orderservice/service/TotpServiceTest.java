package com.example.orderservice.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {
    private final TotpService totp = new TotpService("test-service-key");

    // RFC 6238 appendix B: the SHA-1 secret "12345678901234567890" gives these 6-digit codes at these times.
    private static final String RFC_SECRET = TotpService.base32("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238TestVectors() {
        assertEquals("287082", totp.codeForStep(RFC_SECRET, 59L / 30));
        assertEquals("081804", totp.codeForStep(RFC_SECRET, 1111111109L / 30));
        assertEquals("050471", totp.codeForStep(RFC_SECRET, 1111111111L / 30));
        assertEquals("005924", totp.codeForStep(RFC_SECRET, 1234567890L / 30));
        assertEquals("279037", totp.codeForStep(RFC_SECRET, 2000000000L / 30));
    }

    @Test
    void base32RoundTripsAndNewSecretsDiffer() {
        String secret = totp.newSecret();

        assertEquals(32, secret.length());          // 160 bits
        assertEquals(secret, TotpService.base32(TotpService.unbase32(secret)));
        assertNotEquals(secret, totp.newSecret());
        assertThrows(IllegalArgumentException.class, () -> TotpService.unbase32("not*base32"));
    }

    @Test
    void acceptsTheCurrentAndNeighbouringStepsButNotOlderOnes() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = totp.stepAt(now);

        assertEquals(step, totp.matchingStep(RFC_SECRET, totp.codeForStep(RFC_SECRET, step), now));
        assertEquals(step - 1, totp.matchingStep(RFC_SECRET, totp.codeForStep(RFC_SECRET, step - 1), now));
        assertEquals(step + 1, totp.matchingStep(RFC_SECRET, totp.codeForStep(RFC_SECRET, step + 1), now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, totp.codeForStep(RFC_SECRET, step - 3), now));
    }

    @Test
    void malformedCodesNeverMatch() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        assertEquals(-1, totp.matchingStep(RFC_SECRET, null, now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, "12345", now));
        assertEquals(-1, totp.matchingStep(RFC_SECRET, "abcdef", now));
    }

    @Test
    void theStoredSecretIsEncryptedAndOnlyTheSameKeyReadsItBack() {
        String stored = totp.encrypt(RFC_SECRET);

        assertTrue(!stored.contains(RFC_SECRET));
        assertEquals(RFC_SECRET, totp.decrypt(stored));
        assertNotEquals(stored, totp.encrypt(RFC_SECRET));                    // fresh IV every time
        assertThrows(IllegalStateException.class, () -> new TotpService("another-key").decrypt(stored));
    }

    @Test
    void theAuthenticatorUriCarriesTheSecretAndIssuer() {
        String uri = totp.otpauthUri("asha", RFC_SECRET, "Charan Mart admin");

        assertTrue(uri.startsWith("otpauth://totp/Charan%20Mart%20admin%3Aasha?secret=" + RFC_SECRET));
        assertTrue(uri.contains("issuer=Charan%20Mart%20admin"));
        assertTrue(uri.contains("period=30") && uri.contains("digits=6"));
    }
}
