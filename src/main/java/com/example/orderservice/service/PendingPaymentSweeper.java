package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves unpaid UPI orders on a timer. Before this, an order sitting in PENDING_PAYMENT was only cancelled (and
 * its reserved stock put back) when someone happened to poll it, so a buyer who walked away left that stock stuck
 * indefinitely. fixedDelay so a slow sweep (PhonepayService timeouts) never overlaps the next one. Switch off with
 * sweeper.enabled=false (the test profile does).
 */
@Component
@ConditionalOnProperty(name = "sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class PendingPaymentSweeper {
    private static final Logger log = LoggerFactory.getLogger(PendingPaymentSweeper.class);

    private final OrderService orderService;

    public PendingPaymentSweeper(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(initialDelayString = "${sweeper.initial-delay-ms:30000}", fixedDelayString = "${sweeper.interval-ms:60000}")
    public void run() {
        try {
            orderService.sweepPendingPayments();
        } catch (RuntimeException e) {
            // A failed sweep must never kill the scheduler thread - the next tick just tries again.
            log.error("Pending payment sweep failed: {}", e.getMessage(), e);
        }
    }
}
