package com.example.orderservice.security;

import com.example.orderservice.exception.CustomerAuthException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginRateLimiterTest {

    // A clock the test can move forward.
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ip 3/h, phone 2/h, email 2/h; verify ip 4 and phone 3 per 15 min
    private LoginRateLimiter limiter(MutableClock clock) {
        return new LoginRateLimiter(clock, true, 3, 2, 2, 60, 4, 3, 15, 3, 2, 15);
    }

    @Test
    void aPhoneCannotRequestMoreCodesThanItsHourlyLimit() {
        LoginRateLimiter l = limiter(new MutableClock());
        l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
        l.checkCodeRequest("1.1.1.2", 9876543210L, "b@example.com");

        CustomerAuthException e = assertThrows(CustomerAuthException.class,
                () -> l.checkCodeRequest("1.1.1.3", 9876543210L, "c@example.com"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }

    @Test
    void oneEmailCannotBeMailBombedByCyclingPhones() {
        LoginRateLimiter l = limiter(new MutableClock());
        l.checkCodeRequest("1.1.1.1", 9000000001L, "Victim@Example.com");
        l.checkCodeRequest("1.1.1.2", 9000000002L, " victim@example.com ");

        assertThrows(CustomerAuthException.class, () -> l.checkCodeRequest("1.1.1.3", 9000000003L, "VICTIM@example.com"));
    }

    @Test
    void oneAddressCannotSprayManyPhonesAndEmails() {
        LoginRateLimiter l = limiter(new MutableClock());
        l.checkCodeRequest("9.9.9.9", 9000000001L, "a@example.com");
        l.checkCodeRequest("9.9.9.9", 9000000002L, "b@example.com");
        l.checkCodeRequest("9.9.9.9", 9000000003L, "c@example.com");

        assertThrows(CustomerAuthException.class, () -> l.checkCodeRequest("9.9.9.9", 9000000004L, "d@example.com"));
        assertDoesNotThrow(() -> l.checkCodeRequest("8.8.8.8", 9000000004L, "d@example.com"));
    }

    // admin: ip 3, username 2 per 15 min
    @Test
    void anAdminUsernameCannotBeGuessedAtBeyondItsLimitWhateverTheAddress() {
        LoginRateLimiter l = limiter(new MutableClock());
        l.checkAdminLogin("1.1.1.1", "Asha");
        l.checkAdminLogin("1.1.1.2", " asha ");

        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> l.checkAdminLogin("1.1.1.3", "ASHA"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertDoesNotThrow(() -> l.checkAdminLogin("1.1.1.3", "someone-else"));
    }

    @Test
    void oneAddressCannotTryManyAdminUsernames() {
        LoginRateLimiter l = limiter(new MutableClock());
        l.checkAdminLogin("9.9.9.9", "a-one");
        l.checkAdminLogin("9.9.9.9", "b-two");
        l.checkAdminLogin("9.9.9.9", "c-three");

        assertThrows(CustomerAuthException.class, () -> l.checkAdminLogin("9.9.9.9", "d-four"));
        assertDoesNotThrow(() -> l.checkAdminLogin("8.8.8.8", "d-four"));
    }

    @Test
    void adminLoginLimitsLiftAfterTheirWindowAndAreSeparateFromCustomerLimits() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter l = limiter(clock);
        l.checkAdminLogin("1.1.1.1", "asha");
        l.checkAdminLogin("1.1.1.1", "asha");
        assertThrows(CustomerAuthException.class, () -> l.checkAdminLogin("1.1.1.1", "asha"));
        assertDoesNotThrow(() -> l.checkVerify("1.1.1.1", 9876543210L));

        clock.advance(Duration.ofMinutes(16));

        assertDoesNotThrow(() -> l.checkAdminLogin("1.1.1.1", "asha"));
    }

    @Test
    void limitsLiftOnceTheWindowHasPassed() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter l = limiter(clock);
        l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
        l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
        assertThrows(CustomerAuthException.class, () -> l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com"));

        clock.advance(Duration.ofMinutes(61));

        assertDoesNotThrow(() -> l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com"));
    }

    @Test
    void aRefusedAttemptDoesNotExtendTheBlock() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter l = limiter(clock);
        l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
        l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
        clock.advance(Duration.ofMinutes(30));
        assertThrows(CustomerAuthException.class, () -> l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com"));
        assertThrows(CustomerAuthException.class, () -> l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com"));

        clock.advance(Duration.ofMinutes(31)); // 61 minutes after the two counted hits

        assertDoesNotThrow(() -> l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com"));
    }

    @Test
    void verifyAttemptsAreLimitedPerPhoneAndPerAddress() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter l = limiter(clock);
        l.checkVerify("1.1.1.1", 9876543210L);
        l.checkVerify("1.1.1.2", 9876543210L);
        l.checkVerify("1.1.1.3", 9876543210L);
        assertThrows(CustomerAuthException.class, () -> l.checkVerify("1.1.1.4", 9876543210L));

        // a different phone from one address: 4 allowed, the 5th refused
        l.checkVerify("5.5.5.5", 9000000001L);
        l.checkVerify("5.5.5.5", 9000000002L);
        l.checkVerify("5.5.5.5", 9000000003L);
        l.checkVerify("5.5.5.5", 9000000004L);
        assertThrows(CustomerAuthException.class, () -> l.checkVerify("5.5.5.5", 9000000005L));

        clock.advance(Duration.ofMinutes(16));
        assertDoesNotThrow(() -> l.checkVerify("1.1.1.1", 9876543210L));
    }

    @Test
    void disabledLimiterNeverBlocks() {
        LoginRateLimiter l = new LoginRateLimiter(new MutableClock(), false, 1, 1, 1, 60, 1, 1, 15, 1, 1, 15);
        for (int i = 0; i < 10; i++) {
            l.checkCodeRequest("1.1.1.1", 9876543210L, "a@example.com");
            l.checkVerify("1.1.1.1", 9876543210L);
            l.checkAdminLogin("1.1.1.1", "asha");
        }
    }
}
