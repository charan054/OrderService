package com.example.orderservice.security;

import com.example.orderservice.service.AdminAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads X-Admin-Token (a named admin's session from AdminAuthService) and, if valid, authenticates the request with
 * an AdminPrincipal. Like the other authentication filters it only establishes identity and never rejects.
 * The authorities are ROLE_SERVICE - the existing "trusted caller" rules in SecurityConfig and CustomerAccess then
 * apply unchanged - plus ROLE_ADMIN; what that admin may actually do is narrowed afterwards by AdminPolicyFilter.
 * A request that already carries a valid X-Service-Key keeps the service key's identity.
 */
public class AdminTokenAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Admin-Token";

    private final AdminAuthService adminAuthService;

    public AdminTokenAuthenticationFilter(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            adminAuthService.resolveToken(request.getHeader(HEADER)).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_SERVICE"), new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
