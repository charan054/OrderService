package com.example.orderservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Reads X-Service-Key and, if it matches, authenticates the request as ROLE_SERVICE. Leaves the request
 * unauthenticated otherwise (SecurityConfig's authorization rules decide what that means for a given endpoint) -
 * this filter only establishes identity, it never itself rejects a request.
 */
public class ServiceKeyAuthenticationFilter extends OncePerRequestFilter {
    private final String expectedKey;

    public ServiceKeyAuthenticationFilter(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-Service-Key");
        if (matches(provided, expectedKey)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    // MessageDigest.isEqual compares every byte regardless of where the first mismatch is, unlike String.equals,
    // so a caller measuring response timing can't recover the key one byte at a time.
    private boolean matches(String provided, String expected) {
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
