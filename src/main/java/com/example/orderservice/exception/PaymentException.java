package com.example.orderservice.exception;

import org.springframework.http.HttpStatus;

// Wraps a failure from PhonepayService's own makepayment call (bad/expired token, insufficient funds, locked
// account, bank unavailable, ...) so the caller of /cart/add sees PhonepayService's real status and message
// instead of an opaque 500.
public class PaymentException extends RuntimeException {
    private final HttpStatus status;

    public PaymentException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
