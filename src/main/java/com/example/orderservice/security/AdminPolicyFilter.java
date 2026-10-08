package com.example.orderservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Enforces AdminPolicy for requests made with a named admin account: a role that is too low gets 403 with a message
 * the dashboard can show. Requests made with the service key or a customer session are not touched. Sits after
 * AuditLogFilter so a refused attempt is still recorded, under the admin's name.
 */
public class AdminPolicyFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal admin
                && !AdminPolicy.allows(admin.role(), request.getMethod(), request.getRequestURI())) {
            var needed = AdminPolicy.requiredRole(request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(("Your role (" + admin.role() + ") cannot do this - it needs " + needed + " or higher.")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
