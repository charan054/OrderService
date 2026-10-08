package com.example.orderservice.dto;

import com.example.orderservice.entity.AdminRole;

public record NewAdminAccount(String username, String password, AdminRole role) {
}
