package com.example.orderservice.security;

import com.example.orderservice.service.CustomerAuthService;
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
 * Reads X-Customer-Token (a storefront session from CustomerAuthService) and, if valid, authenticates the request
 * as ROLE_CUSTOMER with the session's phone number as the principal. Like ServiceKeyAuthenticationFilter it only
 * establishes identity and never rejects; a request that already carries a valid X-Service-Key keeps ROLE_SERVICE.
 * Which phone number a customer may act on is enforced per endpoint by CustomerAccess.
 */
public class CustomerTokenAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Customer-Token";

    private final CustomerAuthService customerAuthService;

    public CustomerTokenAuthenticationFilter(CustomerAuthService customerAuthService) {
        this.customerAuthService = customerAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            customerAuthService.resolveToken(request.getHeader(HEADER)).ifPresent(phno -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        phno, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
