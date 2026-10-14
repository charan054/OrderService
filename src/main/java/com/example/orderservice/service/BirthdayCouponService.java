package com.example.orderservice.service;

import com.example.orderservice.dto.BirthdayCouponResult;
import com.example.orderservice.dto.BulkCouponRequest;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Birthday coupons. A customer can save the day and month of their birthday (no year - nothing here needs their age).
 * During the days around it, the daily job emails them a single-use coupon, once per year, unless they unsubscribed
 * from marketing email or have no email on file. The coupon is an ordinary unlisted one-redemption code minted by
 * CouponBatchService, so checkout needs no changes.
 */
@Service
public class BirthdayCouponService {
    private static final Logger log = LoggerFactory.getLogger(BirthdayCouponService.class);

    private final CustomerAccountRepository accounts;
    private final CouponBatchService couponBatch;
    private final MailService mailService;
    private final EmailPreferenceService preferences;
    private final Clock clock;
    private final double percent;
    private final int windowDays;
    private final int validDays;
    private final ZoneId zone;
    private final String shopUrl;

    public BirthdayCouponService(CustomerAccountRepository accounts, CouponBatchService couponBatch, MailService mailService,
                                 EmailPreferenceService preferences, Clock clock,
                                 @Value("${birthday.coupon.percent:10}") double percent,
                                 @Value("${birthday.coupon.window-days:3}") int windowDays,
                                 @Value("${birthday.coupon.valid-days:14}") int validDays,
                                 @Value("${birthday.coupon.zone:Asia/Kolkata}") String zone,
                                 @Value("${alerts.shop-url:}") String shopUrl) {
        this.accounts = accounts;
        this.couponBatch = couponBatch;
        this.mailService = mailService;
        this.preferences = preferences;
        this.clock = clock;
        this.percent = percent;
        this.windowDays = Math.max(0, windowDays);
        this.validDays = Math.max(1, validDays);
        this.zone = ZoneId.of(zone);
        this.shopUrl = shopUrl == null ? "" : shopUrl.trim();
    }

    /** "MM-DD", or empty when none is saved. */
    public Optional<String> get(long phno) {
        return accounts.findById(phno)
                .filter(a -> a.getBirthMonth() != null && a.getBirthDay() != null)
                .map(a -> String.format("%02d-%02d", a.getBirthMonth(), a.getBirthDay()));
    }

    public String set(long phno, int month, int day) {
        try {
            MonthDay.of(month, day); // Feb 29 is accepted
        } catch (DateTimeException e) {
            throw new ProductException("That is not a valid date");
        }
        CustomerAccount account = accounts.findById(phno)
                .orElseThrow(() -> new ProductException("Sign in with your email first"));
        account.setBirthMonth(month);
        account.setBirthDay(day);
        accounts.save(account);
        return String.format("%02d-%02d", month, day);
    }

    public void clear(long phno) {
        accounts.findById(phno).ifPresent(account -> {
            account.setBirthMonth(null);
            account.setBirthDay(null);
            accounts.save(account);
        });
    }

    /**
     * Pure: the year of the birthday occurrence that is within windowDays of today, or null when there is none. Looks at
     * last year, this year and next year so a birthday near New Year works from either side.
     */
    static Integer occurrenceYearNear(MonthDay birthday, LocalDate today, int windowDays) {
        for (int year = today.getYear() - 1; year <= today.getYear() + 1; year++) {
            long distance = Math.abs(ChronoUnit.DAYS.between(birthday.atYear(year), today));
            if (distance <= windowDays) {
                return year;
            }
        }
        return null;
    }

    public BirthdayCouponResult run() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        int sent = 0, noEmail = 0, optedOut = 0, failed = 0;
        List<CustomerAccount> all = accounts.findAll();
        for (CustomerAccount account : all) {
            if (account.getBirthMonth() == null || account.getBirthDay() == null) {
                continue;
            }
            Integer year;
            try {
                year = occurrenceYearNear(MonthDay.of(account.getBirthMonth(), account.getBirthDay()), today, windowDays);
            } catch (DateTimeException e) {
                continue;
            }
            if (year == null || (account.getBirthdayCouponYear() != null && account.getBirthdayCouponYear() >= year)) {
                continue;
            }
            if (account.getEmail() == null || account.getEmail().isBlank()) {
                noEmail++;
                continue;
            }
            if (account.isMarketingOptOut()) {
                optedOut++;
                continue;
            }
            Instant expires = now.plus(Duration.ofDays(validDays));
            String code = couponBatch.generate(new BulkCouponRequest("BDAY", 1, percent, expires)).codes().get(0);
            if (mailService.send(account.getEmail(), "Happy birthday from Charan Mart!", bodyFor(code, account.getPhno()))) {
                account.setBirthdayCouponYear(year);
                accounts.save(account);
                sent++;
            } else {
                failed++; // the coupon stays unused; the next run tries again with a fresh code
            }
        }
        log.info("Birthday coupons: {} email(s) sent, {} customer(s) without an email, {} unsubscribed, {} send failure(s)",
                sent, noEmail, optedOut, failed);
        return new BirthdayCouponResult(sent, noEmail, optedOut, failed);
    }

    private String bodyFor(String code, long phno) {
        StringBuilder body = new StringBuilder("Happy birthday!\n\nHere is a gift from us: ")
                .append(Math.round(percent)).append("% off your next order with the code\n\n    ").append(code)
                .append("\n\nEnter it at checkout. It works once and is valid for ").append(validDays).append(" days.\n");
        if (!shopUrl.isEmpty()) {
            body.append('\n').append(shopUrl).append('\n');
        }
        body.append(preferences.footer(phno));
        return body.toString();
    }
}
