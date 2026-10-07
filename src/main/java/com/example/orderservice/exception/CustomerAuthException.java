package com.example.orderservice.exception;

import org.springframework.http.HttpStatus;

// A storefront login failure (bad/expired code, too many attempts, resend too soon, ...) with the status to send.
public class CustomerAuthException extends RuntimeException {
    private final HttpStatus status;

    public CustomerAuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
