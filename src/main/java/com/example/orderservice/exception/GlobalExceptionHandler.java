package com.example.orderservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ProductException.class)
    public ResponseEntity<String> handleProductException(ProductException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<String> handlePaymentException(PaymentException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getMessage());
    }

    @ExceptionHandler(CustomerAuthException.class)
    public ResponseEntity<String> handleCustomerAuthException(CustomerAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getMessage());
    }

    @ExceptionHandler(AdminAuthException.class)
    public ResponseEntity<String> handleAdminAuthException(AdminAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<String> handleOrderNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    // A downstream (ProductService/PhonepayService) failure no service method translated itself, e.g. the public
    // review/gallery proxies asked about a product that doesn't exist: a 4xx from downstream is passed through as
    // that same status, anything else (5xx, unreachable) is a 502 - never an opaque 500 from this service.
    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<String> handleFeign(feign.FeignException e) {
        HttpStatus status = HttpStatus.resolve(e.status());
        // 401/403 from downstream mean OUR credentials were refused - not something the caller can fix.
        if (status == null || !status.is4xxClientError() || status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String message = status == HttpStatus.NOT_FOUND ? "Not found"
                : status == HttpStatus.BAD_GATEWAY ? "A backing service is unavailable" : e.contentUTF8();
        return ResponseEntity.status(status).body(message);
    }
}
