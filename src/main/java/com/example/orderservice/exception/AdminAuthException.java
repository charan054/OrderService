package com.example.orderservice.exception;

import org.springframework.http.HttpStatus;

// An admin sign-in or account-management failure (bad credentials, weak password, last owner, ...) with the status to send.
public class AdminAuthException extends RuntimeException {
    private final HttpStatus status;

    public AdminAuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
