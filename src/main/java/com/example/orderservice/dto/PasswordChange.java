package com.example.orderservice.dto;

// currentPassword is required when changing your own password and ignored when an owner resets someone else's.
public record PasswordChange(String currentPassword, String newPassword) {
}
