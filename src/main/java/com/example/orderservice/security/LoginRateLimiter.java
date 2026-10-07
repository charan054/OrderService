package com.example.orderservice.security;

import com.example.orderservice.exception.CustomerAuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sliding-window limits on the public sign-in endpoints, on top of the per-phone 60-second resend cooldown and the
 * 5-guesses-per-code cap in CustomerAuthService (which alone still let one phone be guessed at or mailed
 * indefinitely, one code per minute):
 * <ul>
 *   <li>code requests - per client address, per phone, and per target email (the last stops someone mail-bombing
 *   a victim's inbox by cycling phone numbers, since for a not-yet-bound phone the code goes to whatever email was
 *   typed);</li>
 *   <li>verify attempts - per client address and per phone, counted whether right or wrong, so guessing a 6-digit
 *   code can't be spread across fresh codes.</li>
 * </ul>
 * In memory and per instance: counters reset on restart and are not shared between instances - fine for this
 * single-instance service, and noted here so nobody scales it out assuming otherwise. Behind a reverse proxy the
 * "client address" is the proxy's unless forwarded headers are configured (the storefront calls this service
 * directly today).
 */
@Component
public class LoginRateLimiter {
    private static final int PURGE_EVERY = 500;

    private final Clock clock;
    private final boolean enabled;
    private final int requestsPerIp;
    private final int requestsPerPhone;
    private final int requestsPerEmail;
    private final Duration requestWindow;
    private final int verifiesPerIp;
    private final int verifiesPerPhone;
    private final Duration verifyWindow;

    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private int callsSincePurge;

    public LoginRateLimiter(Clock clock,
                            @Value("${customer.login.rate-limit.enabled:true}") boolean enabled,
                            @Value("${customer.login.rate-limit.requests-per-ip:20}") int requestsPerIp,
                            @Value("${customer.login.rate-limit.requests-per-phone:5}") int requestsPerPhone,
                            @Value("${customer.login.rate-limit.requests-per-email:5}") int requestsPerEmail,
                            @Value("${customer.login.rate-limit.request-window-minutes:60}") long requestWindowMinutes,
                            @Value("${customer.login.rate-limit.verifies-per-ip:40}") int verifiesPerIp,
                            @Value("${customer.login.rate-limit.verifies-per-phone:15}") int verifiesPerPhone,
                            @Value("${customer.login.rate-limit.verify-window-minutes:15}") long verifyWindowMinutes) {
        this.clock = clock;
        this.enabled = enabled;
        this.requestsPerIp = requestsPerIp;
        this.requestsPerPhone = requestsPerPhone;
        this.requestsPerEmail = requestsPerEmail;
        this.requestWindow = Duration.ofMinutes(requestWindowMinutes);
        this.verifiesPerIp = verifiesPerIp;
        this.verifiesPerPhone = verifiesPerPhone;
        this.verifyWindow = Duration.ofMinutes(verifyWindowMinutes);
    }

    // Counts this attempt against every limit and throws 429 if any is already used up. Nothing is counted for an
    // attempt that is itself refused, so a blocked caller can't extend their own block by retrying.
    public void checkCodeRequest(String ip, long phno, String email) {
        if (!enabled) {
            return;
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        consume(requestWindow,
                new Limit("req:ip:" + ip, requestsPerIp),
                new Limit("req:phone:" + phno, requestsPerPhone),
                new Limit("req:email:" + normalizedEmail, requestsPerEmail));
    }

    public void checkVerify(String ip, long phno) {
        if (!enabled) {
            return;
        }
        consume(verifyWindow,
                new Limit("ver:ip:" + ip, verifiesPerIp),
                new Limit("ver:phone:" + phno, verifiesPerPhone));
    }

    private record Limit(String key, int max) {
    }

    private synchronized void consume(Duration window, Limit... limits) {
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        if (++callsSincePurge >= PURGE_EVERY) {
            purge(cutoff - window.toMillis());
            callsSincePurge = 0;
        }
        for (Limit limit : limits) {
            Deque<Long> times = hits.get(limit.key());
            if (times == null) {
                continue;
            }
            while (!times.isEmpty() && times.peekFirst() <= cutoff) {
                times.pollFirst();
            }
            if (times.size() >= limit.max()) {
                throw new CustomerAuthException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many attempts. Please wait a while and try again.");
            }
        }
        for (Limit limit : limits) {
            hits.computeIfAbsent(limit.key(), k -> new ArrayDeque<>()).addLast(now);
        }
    }

    // Drops keys whose newest hit is older than any window we use, so the map can't grow without bound.
    private void purge(long olderThan) {
        hits.values().removeIf(times -> times.isEmpty() || times.peekLast() < olderThan);
    }
}
