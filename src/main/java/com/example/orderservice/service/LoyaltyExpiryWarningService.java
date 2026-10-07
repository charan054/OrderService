package com.example.orderservice.service;

import com.example.orderservice.dto.LoyaltyExpiryWarningResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Points silently expire after a year without account activity (see OrderService.applyPointsExpiry). This warns a
 * customer by email a couple of weeks before it happens, so they can spend them. Like the other emails it goes to
 * the address verified at sign-in, and state (expiryWarnedAt) only advances after the send succeeded - a customer
 * without a verified email, or a mail failure, is simply retried on the next run.
 *
 * "Once per expiry": the warning is remembered against the activity window it was sent in; any later activity
 * moves lastActivityAt past it and arms the next warning.
 */
@Service
public class LoyaltyExpiryWarningService {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyExpiryWarningService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final LoyaltyAccountRepository accounts;
    private final CustomerAccountRepository customers;
    private final MailService mailService;
    private final EmailPreferenceService preferences;
    private final Clock clock;
    private final int warningDays;
    private final ZoneId zone;

    public LoyaltyExpiryWarningService(LoyaltyAccountRepository accounts, CustomerAccountRepository customers,
                                       MailService mailService, EmailPreferenceService preferences, Clock clock,
                                       @Value("${loyalty.warning.days:14}") int warningDays,
                                       @Value("${loyalty.warning.zone:Asia/Kolkata}") String zone) {
        this.accounts = accounts;
        this.customers = customers;
        this.mailService = mailService;
        this.preferences = preferences;
        this.clock = clock;
        this.warningDays = warningDays;
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
    }

    public LoyaltyExpiryWarningResult run() {
        Instant now = clock.instant();
        // Expires at lastActivityAt + 365d, so "expires within N days" means lastActivityAt in [now-365d, now-(365-N)d].
        Instant from = now.minus(LoyaltyAccount.POINTS_EXPIRY_DAYS, ChronoUnit.DAYS);
        Instant to = now.minus(LoyaltyAccount.POINTS_EXPIRY_DAYS - warningDays, ChronoUnit.DAYS);
        List<LoyaltyAccount> expiring = accounts.findByPointsBalanceGreaterThanAndLastActivityAtBetween(0, from, to);

        int sent = 0;
        int noEmail = 0;
        int optedOut = 0;
        int failed = 0;
        for (LoyaltyAccount account : expiring) {
            if (account.getExpiryWarnedAt() != null && account.getExpiryWarnedAt().isAfter(account.getLastActivityAt())) {
                continue; // already warned for this expiry
            }
            CustomerAccount customer = customers.findById(account.getCustomerPhno()).orElse(null);
            String email = customer == null ? null : customer.getEmail();
            if (email == null || email.isBlank()) {
                noEmail++;
                continue;
            }
            if (customer.isMarketingOptOut()) {
                optedOut++;
                continue;
            }
            Instant expiresAt = account.getPointsExpireAt();
            String subject = account.getPointsBalance() + " loyalty points expire on " + DATE.format(expiresAt.atZone(zone));
            String body = "Hi,\n\nYou have " + account.getPointsBalance() + " loyalty points at Charan Mart (1 point = Rs. 1), and "
                    + "they expire on " + DATE.format(expiresAt.atZone(zone)) + " because there has been no activity on your account "
                    + "for a year.\n\nUse them at checkout before then to keep their value - you can redeem up to the order total "
                    + "after any coupon.\n"
                    + preferences.footer(account.getCustomerPhno());
            if (mailService.send(email, subject, body)) {
                account.setExpiryWarnedAt(now);
                accounts.save(account);
                sent++;
            } else {
                failed++;
            }
        }
        log.info("Loyalty expiry warnings: {} email(s) sent, {} customer(s) without an email, {} unsubscribed, {} send failure(s)", sent, noEmail, optedOut, failed);
        return new LoyaltyExpiryWarningResult(sent, noEmail, optedOut, failed);
    }
}
