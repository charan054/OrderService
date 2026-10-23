package com.example.orderservice.dto;

/** code: the 6-digit authenticator code (or a recovery code) - only needed by accounts that turned on two-step sign-in. */
public record AdminLoginRequest(String username, String password, String code) {
    public AdminLoginRequest(String username, String password) {
        this(username, password, null);
    }
}
