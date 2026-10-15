package com.example.orderservice.security;

import com.example.orderservice.repository.AuditLogRepository;
import com.example.orderservice.service.AdminAuthService;
import com.example.orderservice.service.CustomerAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * Three trust levels:
 * <ul>
 *   <li>Public - catalog browsing, sign-in, and order-id-scoped status lookups that expose no customer details.</li>
 *   <li>Customer-or-service - anything that reads or changes ONE customer's own data (orders, wishlist, addresses,
 *   loyalty, ...). A storefront customer needs a verified session (X-Customer-Token, see CustomerAuthService) and
 *   may only touch their own phone number (enforced per endpoint by CustomerAccess); the admin dashboard's
 *   X-Service-Key may touch any. Before verified login these were public to anyone who typed a phone number.</li>
 *   <li>Everything else - service only: placing orders for others, listing every customer's orders, ship/deliver,
 *   analytics, coupons admin, ... A customer token never satisfies these. "Service" here means the shared
 *   X-Service-Key OR a named admin's X-Admin-Token (AdminAuthService); the latter is then held to its role by
 *   AdminPolicy, which the service key is not.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final String serviceApiKey;
    private final CustomerAuthService customerAuthService;
    private final AdminAuthService adminAuthService;
    private final NamedLoginPolicy namedLoginPolicy;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public SecurityConfig(@Value("${internal.service.api-key}") String serviceApiKey,
                          CustomerAuthService customerAuthService, AdminAuthService adminAuthService,
                          NamedLoginPolicy namedLoginPolicy, AuditLogRepository auditLogRepository, Clock clock) {
        this.serviceApiKey = serviceApiKey;
        this.customerAuthService = customerAuthService;
        this.adminAuthService = adminAuthService;
        this.namedLoginPolicy = namedLoginPolicy;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ---- Public ----
                        // Catalog browsing and the storefront's product-detail extras (ratings, reviews, gallery,
                        // frequently-bought-together) - no customer's data.
                        .requestMatchers(HttpMethod.GET, "/track/*", "/bundles", "/quantitybreaks", "/cart/display", "/cart/returnpolicy", "/cart/cancelpolicy", "/cart/deliveryfee", "/cart/frequentlyboughttogether", "/cart/search", "/cart/ratings", "/cart/reviews", "/cart/gallery", "/pincodes/check", "/pincodes/slots", "/pincodes/slots/availability", "/questions/product", "/prefs/unsubscribe", "/subscriptions/terms", "/wishlist/shared/*", "/faq", "/review-photos/*").permitAll()
                        // Signing in, and the forgot-PIN proxy to PhonepayService - by definition used before the
                        // customer has a session.
                        .requestMatchers(HttpMethod.POST, "/customer/login/request", "/customer/login/verify", "/customer/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/cart/forgotpin/request", "/cart/forgotpin/reset").permitAll()
                        // An admin signing in (no session yet) or out (only ever ends the caller's own token).
                        .requestMatchers(HttpMethod.POST, "/admin/login", "/admin/logout").permitAll()
                        // Whether the dashboard must sign in by name - asked before anyone has.
                        .requestMatchers(HttpMethod.GET, "/admin/config").permitAll()
                        // Order-id-scoped status only: the tracking timeline and the notification audit trail are
                        // status + timestamps, and /summary (the logged-out "Track an order" box) additionally
                        // requires the matching phone number and returns no address or items.
                        .requestMatchers(HttpMethod.GET, "/cart/*/tracking", "/cart/*/notifications", "/cart/*/summary").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // The static dashboards themselves - just the HTML/JS shell; every call they make is
                        // authorized on its own.
                        .requestMatchers(HttpMethod.GET, "/cart.html", "/shop.html", "/wishlist.html").permitAll()
                        // Their stylesheets and scripts (static/css, static/js) - code only, no data.
                        .requestMatchers(HttpMethod.GET, "/css/*.css", "/js/*.js").permitAll()
                        // Installable-app files: manifest, icon, offline page and the service worker (no data).
                        .requestMatchers(HttpMethod.GET, "/manifest.webmanifest", "/icon.svg", "/offline.html", "/sw.js").permitAll()
                        // A controller-level failure (e.g. a missing required header) triggers an internal
                        // dispatch to /error; the auth filters don't re-run on that dispatch (OncePerRequestFilter
                        // skips ERROR dispatches by default), so without this the real error status gets clobbered
                        // by a spurious 401 from the unauthenticated /error request.
                        .requestMatchers("/error").permitAll()

                        // ---- Customer (own data only, see CustomerAccess) or service ----
                        .requestMatchers(HttpMethod.GET, "/customer/session").hasRole("CUSTOMER")
                        // Deleting your own account: the customer session only (never the service key), and the phone
                        // number is taken from the session.
                        .requestMatchers(HttpMethod.GET, "/customer/account/delete/preview").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/customer/account/delete/request", "/customer/account/delete/confirm").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/cart/byphno", "/cart/history", "/cart/history/export", "/cart/recommendations", "/cart/notifications", "/cart/*/invoice", "/cart/*/invoice.pdf", "/cart/*/refunds", "/cart/*/exchanges", "/cart/exchanges/mine", "/cart/*/delivery-attempts", "/cart/*/gift-receipt", "/cart/*/timeline", "/cart/*/paymentstatus",
                                "/wishlist/byphno", "/wishlist/pricedrops", "/wishlist/share", "/waitlist/byphno", "/searchwatch/byphno", "/addresses/byphno",
                                "/loyalty/byphno", "/loyalty/history", "/customer/profile", "/customer/birthday", "/customer/export", "/coupons/available", "/referral/mine", "/questions/mine", "/pricematch/mine", "/savedcart", "/feedback/mine", "/prefs/mine", "/customer/cod", "/storecredit/byphno", "/support/tickets/mine", "/subscriptions/mine").hasAnyRole("CUSTOMER", "SERVICE")
                        // checkout/cancel/return still also need the buyer's own PhonePe credentials for anything
                        // that moves money (see OrderController) - the session only proves who the customer is.
                        .requestMatchers(HttpMethod.POST, "/cart/checkout", "/cart/*/return/request", "/cart/reviews", "/cart/reviews/photo", "/cart/reviews/flag", "/cart/*/cancel", "/cart/*/return",
                                "/cart/*/items/*/cancel", "/cart/*/items/*/return", "/cart/*/exchange", "/cart/exchanges/*/withdraw", "/cart/*/items/add", "/cart/*/invoice/email", "/cart/*/share",
                                "/wishlist/self/add", "/wishlist/share", "/waitlist/self/add", "/searchwatch/self/add", "/addresses/self/add", "/referral/apply", "/questions/ask", "/pricematch/request", "/feedback/submit", "/support/tickets", "/support/tickets/*/reply", "/giftcards/redeem", "/subscriptions").hasAnyRole("CUSTOMER", "SERVICE")
                        .requestMatchers(HttpMethod.PUT, "/customer/birthday", "/savedcart", "/addresses/self/default", "/cart/*/delivery", "/cart/*/address", "/prefs/mine", "/subscriptions/*/pause", "/subscriptions/*/resume", "/subscriptions/*/skip").hasAnyRole("CUSTOMER", "SERVICE")
                        .requestMatchers(HttpMethod.DELETE, "/cart/*/share", "/customer/birthday", "/wishlist/self/remove", "/wishlist/share", "/waitlist/self/remove", "/searchwatch/self/remove", "/addresses/self/remove", "/subscriptions/*").hasAnyRole("CUSTOMER", "SERVICE")

                        // ---- Service only ----
                        .anyRequest().hasRole("SERVICE"))
                // 401 for no/invalid credentials; a signed-in customer hitting a service-only endpoint gets the
                // default 403 from the access-denied handler.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ServiceKeyAuthenticationFilter(serviceApiKey, namedLoginPolicy::refusesServiceKey), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AdminTokenAuthenticationFilter(adminAuthService), ServiceKeyAuthenticationFilter.class)
                .addFilterAfter(new CustomerTokenAuthenticationFilter(customerAuthService), AdminTokenAuthenticationFilter.class)
                .addFilterAfter(new AuditLogFilter(auditLogRepository, clock), CustomerTokenAuthenticationFilter.class)
                // After the audit filter so a refused attempt is still recorded.
                .addFilterAfter(new AdminPolicyFilter(), AuditLogFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
