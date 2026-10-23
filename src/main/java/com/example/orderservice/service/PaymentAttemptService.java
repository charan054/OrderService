package com.example.orderservice.service;

import com.example.orderservice.entity.PaymentAttempt;
import com.example.orderservice.entity.PaymentAttempt.Status;
import com.example.orderservice.repository.PaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The money trail of checkout. Recording is best effort: a problem writing a row is logged and never fails or delays the
 * checkout itself (the order flow must not depend on its own safety net).
 */
@Service
public class PaymentAttemptService {
    private static final Logger log = LoggerFactory.getLogger(PaymentAttemptService.class);
    // STARTED this long ago and still not CHARGED/DECLINED: the call to PhonepayService timed out or the process died.
    static final Duration UNKNOWN_AFTER = Duration.ofMinutes(5);
    // CHARGED this long ago and still no order: the order step failed after the money was taken.
    static final Duration NO_ORDER_AFTER = Duration.ofMinutes(2);

    private final PaymentAttemptRepository attempts;
    private final Clock clock;

    public PaymentAttemptService(PaymentAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
    }

    /** Call just before the charge is sent. Returns null if it could not be recorded. */
    public PaymentAttempt begin(long phno, double amount, String idempotencyKey) {
        try {
            PaymentAttempt a = new PaymentAttempt();
            a.setCustomerPhno(phno);
            a.setAmount(amount);
            a.setIdempotencyKey(idempotencyKey);
            a.setStatus(Status.STARTED);
            a.setCreatedAt(clock.instant());
            a.setUpdatedAt(clock.instant());
            return attempts.save(a);
        } catch (RuntimeException e) {
            log.error("Could not record the payment attempt for {}: {}", phno, e.getMessage());
            return null;
        }
    }

    public void charged(PaymentAttempt a, long transactionId) {
        update(a, Status.CHARGED, x -> x.setPaymentTransactionId(transactionId));
    }

    public void declined(PaymentAttempt a) {
        update(a, Status.DECLINED, x -> { });
    }

    public void completed(PaymentAttempt a, long orderId) {
        update(a, Status.COMPLETED, x -> x.setOrderId(orderId));
    }

    public void rolledBack(PaymentAttempt a, long orderId, boolean refunded) {
        update(a, refunded ? Status.ROLLED_BACK : Status.ROLLBACK_FAILED, x -> x.setOrderId(orderId));
    }

    private void update(PaymentAttempt a, Status status, java.util.function.Consumer<PaymentAttempt> extra) {
        if (a == null) {
            return;
        }
        try {
            a.setStatus(status);
            a.setUpdatedAt(clock.instant());
            extra.accept(a);
            attempts.save(a);
        } catch (RuntimeException e) {
            log.error("Could not update payment attempt {} to {}: {}", a.getId(), status, e.getMessage());
        }
    }

    /** Charges that very likely moved money but have no order (and no recorded refund). */
    public List<PaymentAttempt> chargedWithoutOrder() {
        return attempts.findByStatusAndResolvedAtIsNullAndUpdatedAtBeforeOrderByIdAsc(Status.CHARGED, clock.instant().minus(NO_ORDER_AFTER));
    }

    /** Charges whose outcome was never learned - check PhonePe to see whether the buyer was debited. */
    public List<PaymentAttempt> outcomeUnknown() {
        return attempts.findByStatusAndResolvedAtIsNullAndUpdatedAtBeforeOrderByIdAsc(Status.STARTED, clock.instant().minus(UNKNOWN_AFTER));
    }

    public List<PaymentAttempt> needingAttention() {
        List<PaymentAttempt> all = new ArrayList<>(chargedWithoutOrder());
        all.addAll(outcomeUnknown());
        return all;
    }

    public PaymentAttempt resolve(long id, String note) {
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A note saying how it was settled is required");
        }
        PaymentAttempt a = attempts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment attempt not found"));
        a.setResolvedAt(Instant.now(clock));
        a.setResolution(note.length() > 300 ? note.substring(0, 300) : note);
        return attempts.save(a);
    }
}
