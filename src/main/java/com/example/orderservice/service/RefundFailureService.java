package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.entity.RefundFailure;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.PaymentException;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private final PhonepeClient phonepeClient;
    private final StoreCreditService storeCreditService;
    private final Clock clock;
    private final String serviceApiKey;

    public RefundFailureService(RefundFailureRepository failures, PhonepeClient phonepeClient,
                                StoreCreditService storeCreditService, Clock clock,
                                @Value("${internal.service.api-key}") String serviceApiKey) {
        this.failures = failures;
        this.phonepeClient = phonepeClient;
        this.storeCreditService = storeCreditService;
        this.clock = clock;
        this.serviceApiKey = serviceApiKey;
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

    /**
     * Tries the failed refund again from here: a PhonePe refund goes back through PhonepayService's merchant endpoint to the
     * account that paid (with a fixed idempotency key, so pressing the button twice can never pay twice), a store credit
     * failure is simply credited. On success the failure is resolved; otherwise it stays open and the reason is
     * thrown back to the caller.
     */
    public RefundFailure retry(long id) {
        RefundFailure f = failures.findById(id).orElseThrow(() -> new OrderNotFoundException("Refund failure not found"));
        if (f.getResolvedAt() != null) {
            throw new ProductException("Already resolved");
        }
        if ("STORE_CREDIT".equals(f.getKind())) {
            storeCreditService.credit(f.getCustomerPhno(), f.getAmount(), StoreCreditTransaction.Type.REVERSED,
                    f.getOrderId(), "Order could not be completed - store credit returned (settled by staff)");
            return resolve(id, "Store credit returned automatically on retry");
        }
        if (f.getPaymentTransactionId() == null) {
            throw new ProductException("This failure has no payment to refund - settle it by hand and resolve it");
        }
        PaymentResponse refund;
        try {
            refund = phonepeClient.merchantRefund(serviceApiKey, f.getPaymentTransactionId(),
                    new RefundRequest("settle-refund-failure-" + id, null));
        } catch (FeignException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
        }
        if (!"COMPLETED".equals(refund.status())) {
            throw new PaymentException(HttpStatus.BAD_GATEWAY,
                    "Refund status is " + refund.status() + ", not completed. Reference: " + refund.transactionId());
        }
        return resolve(id, "Refunded automatically on retry (refund " + refund.transactionId() + ")");
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
