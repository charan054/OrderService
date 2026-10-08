package com.example.orderservice.security;

import com.example.orderservice.entity.AdminRole;

/** Who a signed-in admin request belongs to; the request's principal when it came with a valid X-Admin-Token. */
public record AdminPrincipal(String username, AdminRole role) {
}
