package com.example.orderservice.dto;

import com.example.orderservice.entity.AdminAccount;
import com.example.orderservice.entity.AdminRole;

import java.time.Instant;

/** An admin account as shown to admins - never the password hash. */
public record AdminAccountView(String username, AdminRole role, boolean active, Instant createdAt, String createdBy,
                               Instant lastLoginAt, int failedLogins, Instant lastFailedLoginAt) {
    public static AdminAccountView of(AdminAccount a) {
        return new AdminAccountView(a.getUsername(), a.getRole(), a.isActive(), a.getCreatedAt(), a.getCreatedBy(),
                a.getLastLoginAt(), a.getFailedLogins(), a.getLastFailedLoginAt());
    }
}
