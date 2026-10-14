package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs BirthdayCouponService once a day (birthday.coupon.cron, default 09:00 in birthday.coupon.zone). Off unless
 * birthday.coupon.enabled=true (BIRTHDAY_COUPONS_ENABLED) - these are marketing emails, so they are opt-in for the
 * store. Safe to miss a day: the coupon goes out on whichever run first falls inside the birthday window.
 */
@Component
@ConditionalOnProperty(name = "birthday.coupon.enabled", havingValue = "true")
public class BirthdayCouponScheduler {
    private static final Logger log = LoggerFactory.getLogger(BirthdayCouponScheduler.class);

    private final BirthdayCouponService service;

    public BirthdayCouponScheduler(BirthdayCouponService service) {
        this.service = service;
    }

    @Scheduled(cron = "${birthday.coupon.cron:0 0 9 * * *}", zone = "${birthday.coupon.zone:Asia/Kolkata}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("Birthday coupon run failed: {}", e.getMessage(), e);
        }
    }
}
