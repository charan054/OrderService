package com.example.orderservice.dto;

import com.example.orderservice.entity.AdminRole;

// Who the dashboard is acting as. The shared service key shows up as "service-key" with OWNER powers.
public record AdminIdentity(String username, AdminRole role, boolean serviceKey) {
}
