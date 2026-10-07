package com.example.orderservice.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-endpoint ownership check for the customer-or-service endpoints in SecurityConfig: a trusted caller
 * (X-Service-Key, e.g. the admin cart.html) may act on any phone number, a signed-in storefront customer only on
 * their own. SecurityConfig has already rejected anyone who is neither with 401 before this runs.
 */
public final class CustomerAccess {
    private CustomerAccess() {
    }

    public static void requireSelfOrService(long phno) {
        if (isService()) {
            return;
        }
        Long customer = customerPhno();
        if (customer == null || customer != phno) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only access your own account.");
        }
    }

    // For order-scoped calls: true when the caller may see an order owned by ownerPhno. Callers turn false into a
    // 404 rather than a 403, so another customer's order ids can't be probed for existence.
    public static boolean canAccess(long ownerPhno) {
        if (isService()) {
            return true;
        }
        Long customer = customerPhno();
        return customer != null && customer == ownerPhno;
    }

    private static boolean isService() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SERVICE".equals(a.getAuthority()));
    }

    private static Long customerPhno() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long phno ? phno : null;
    }
}
