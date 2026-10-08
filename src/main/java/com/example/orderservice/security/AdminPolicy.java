package com.example.orderservice.security;

import com.example.orderservice.entity.AdminRole;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Set;

/**
 * The lowest AdminRole allowed to call each endpoint when signed in with a named admin account (X-Admin-Token).
 * First matching rule wins, and anything not listed needs MANAGER - so a new endpoint is closed to SUPPORT until
 * someone deliberately adds it to the SUPPORT list below, rather than open by default. Only OWNER-level rules
 * are wildcards on purpose (/admin/** and /audit/**) so new endpoints there stay owner-only too.
 * The service key is not subject to this: it is the trusted-backend credential and always allowed everything.
 */
public final class AdminPolicy {
    private AdminPolicy() {
    }

    record Rule(Set<String> methods, String path, PathPattern pattern, AdminRole minimum) {
        boolean matches(String method, PathContainer requestPath) {
            return (methods.isEmpty() || methods.contains(method)) && pattern.matches(requestPath);
        }
    }

    private static final Set<String> ANY = Set.of();
    private static final Set<String> GET = Set.of("GET");
    private static final Set<String> POST = Set.of("POST");
    private static final Set<String> PUT = Set.of("PUT");
    private static final Set<String> GET_POST = Set.of("GET", "POST");

    static final List<Rule> RULES = List.of(
            // ---- Signing in/out and your own account: any admin ----
            rule(POST, "/admin/login", AdminRole.SUPPORT),
            rule(POST, "/admin/logout", AdminRole.SUPPORT),
            rule(GET, "/admin/me", AdminRole.SUPPORT),
            rule(PUT, "/admin/me/password", AdminRole.SUPPORT),

            // ---- Owner only: accounts, the audit trail, and handing out money-like balances ----
            rule(ANY, "/admin/**", AdminRole.OWNER),
            rule(ANY, "/audit/**", AdminRole.OWNER),
            rule(POST, "/storecredit/adjust", AdminRole.OWNER),
            rule(POST, "/loyalty/adjust", AdminRole.OWNER),
            // Rebinding a phone to a new email hands the account to whoever holds that email.
            rule(PUT, "/customer/admin/email", AdminRole.OWNER),

            // ---- Support: looking things up and answering customers; nothing that moves money or changes settings ----
            // Browsing the catalog and the other public reads the dashboard makes.
            rule(GET, "/cart/display", AdminRole.SUPPORT),
            rule(GET, "/cart/search", AdminRole.SUPPORT),
            rule(GET, "/cart/frequentlyboughttogether", AdminRole.SUPPORT),
            rule(GET, "/cart/ratings", AdminRole.SUPPORT),
            rule(GET, "/cart/reviews", AdminRole.SUPPORT),
            rule(GET, "/cart/gallery", AdminRole.SUPPORT),
            rule(GET, "/pincodes/check", AdminRole.SUPPORT),
            rule(GET, "/pincodes/slots", AdminRole.SUPPORT),
            rule(GET, "/questions/product", AdminRole.SUPPORT),
            // Orders and customers.
            rule(GET, "/cart/byphno", AdminRole.SUPPORT),
            rule(GET, "/cart/all", AdminRole.SUPPORT),
            rule(GET, "/cart/orders/search", AdminRole.SUPPORT),
            rule(GET, "/cart/history", AdminRole.SUPPORT),
            rule(GET, "/cart/{orderId}/invoice", AdminRole.SUPPORT),
            rule(GET, "/cart/{orderId}/paymentstatus", AdminRole.SUPPORT),
            rule(GET, "/cart/{orderId}/tracking", AdminRole.SUPPORT),
            rule(GET, "/cart/{orderId}/notifications", AdminRole.SUPPORT),
            rule(GET, "/cart/{orderId}/summary", AdminRole.SUPPORT),
            rule(GET, "/customer/admin/lookup", AdminRole.SUPPORT),
            rule(GET, "/wishlist/byphno", AdminRole.SUPPORT),
            rule(GET, "/loyalty/byphno", AdminRole.SUPPORT),
            rule(GET, "/loyalty/history", AdminRole.SUPPORT),
            rule(GET, "/addresses/byphno", AdminRole.SUPPORT),
            rule(GET, "/storecredit/byphno", AdminRole.SUPPORT),
            rule(GET, "/feedback/summary", AdminRole.SUPPORT),
            // Talking to customers.
            rule(GET_POST, "/ordernotes", AdminRole.SUPPORT),
            rule(GET, "/questions/pending", AdminRole.SUPPORT),
            rule(PUT, "/questions/{id}/answer", AdminRole.SUPPORT),
            rule(GET, "/support/admin/tickets", AdminRole.SUPPORT),
            rule(GET, "/support/admin/tickets/{id}", AdminRole.SUPPORT),
            rule(POST, "/support/admin/tickets/{id}/reply", AdminRole.SUPPORT),
            rule(POST, "/support/admin/tickets/{id}/resolve", AdminRole.SUPPORT),
            rule(GET, "/cart/reviews/flagged", AdminRole.SUPPORT));

    private static Rule rule(Set<String> methods, String path, AdminRole minimum) {
        return new Rule(methods, path, PathPatternParser.defaultInstance.parse(path), minimum);
    }

    /** The lowest role allowed to make this request. */
    public static AdminRole requiredRole(String method, String requestUri) {
        PathContainer path = PathContainer.parsePath(requestUri);
        for (Rule rule : RULES) {
            if (rule.matches(method, path)) {
                return rule.minimum();
            }
        }
        return AdminRole.MANAGER;
    }

    public static boolean allows(AdminRole role, String method, String requestUri) {
        return role.atLeast(requiredRole(method, requestUri));
    }
}
