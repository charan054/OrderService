package com.example.orderservice.service;

import com.example.orderservice.entity.RefundFailure;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.RefundFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** The staff worklist of refunds the system could not make by itself. Recording one never throws. */
@Service
public class RefundFailureService {
    private static final Logger log = LoggerFactory.getLogger(RefundFailureService.class);
    static final int MAX_TEXT = 300;

    private final RefundFailureRepository failures;
    private final Clock clock;

    public RefundFailureService(RefundFailureRepository failures, Clock clock) {
        this.failures = failures;
        this.clock = clock;
    }

    /** Called from the middle of a rollback, so a database problem here is logged instead of hiding the real error. */
    public void record(long orderId, long customerPhno, String kind, double amount, Long paymentTransactionId, String reason) {
        try {
            RefundFailure f = new RefundFailure();
            f.setOrderId(orderId);
            f.setCustomerPhno(customerPhno);
            f.setKind(kind);
            f.setAmount(amount);
            f.setPaymentTransactionId(paymentTransactionId);
            f.setReason(clip(reason));
            f.setCreatedAt(clock.instant());
            failures.save(f);
        } catch (RuntimeException e) {
            log.error("Could not record refund failure for order {} ({} {}): {}", orderId, kind, amount, e.getMessage());
        }
    }

    public List<RefundFailure> open() {
        return failures.findByResolvedAtIsNullOrderByIdAsc();
    }

    public RefundFailure resolve(long id, String resolution) {
        String note = resolution == null ? "" : resolution.trim();
        if (note.isEmpty()) {
            throw new ProductException("Say how it was settled (for example: refunded by hand from the PhonePe dashboard)");
        }
        if (note.length() > MAX_TEXT) {
            throw new ProductException("Note must be at most " + MAX_TEXT + " characters");
        }
        RefundFailure f = failures.findById(id).orElseThrow(() -> new OrderNotFoundException("Refund failure not found"));
        if (f.getResolvedAt() != null) {
            throw new ProductException("Already resolved");
        }
        f.setResolvedAt(clock.instant());
        f.setResolution(note);
        return failures.save(f);
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
