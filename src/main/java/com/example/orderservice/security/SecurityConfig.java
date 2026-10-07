package com.example.orderservice.security;

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

/**
 * Three trust levels:
 * <ul>
 *   <li>Public - catalog browsing, sign-in, and order-id-scoped status lookups that expose no customer details.</li>
 *   <li>Customer-or-service - anything that reads or changes ONE customer's own data (orders, wishlist, addresses,
 *   loyalty, ...). A storefront customer needs a verified session (X-Customer-Token, see CustomerAuthService) and
 *   may only touch their own phone number (enforced per endpoint by CustomerAccess); the admin dashboard's
 *   X-Service-Key may touch any. Before verified login these were public to anyone who typed a phone number.</li>
 *   <li>Everything else - service only (X-Service-Key): placing orders for others, listing every customer's orders,
 *   ship/deliver, analytics, coupons admin, ... A customer token never satisfies these.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final String serviceApiKey;
    private final CustomerAuthService customerAuthService;

    public SecurityConfig(@Value("${internal.service.api-key}") String serviceApiKey,
                          CustomerAuthService customerAuthService) {
        this.serviceApiKey = serviceApiKey;
        this.customerAuthService = customerAuthService;
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
                        .requestMatchers(HttpMethod.GET, "/cart/display", "/cart/frequentlyboughttogether", "/cart/search", "/cart/ratings", "/cart/reviews", "/cart/gallery", "/pincodes/check", "/pincodes/slots").permitAll()
                        // Signing in, and the forgot-PIN proxy to PhonepayService - by definition used before the
                        // customer has a session.
                        .requestMatchers(HttpMethod.POST, "/customer/login/request", "/customer/login/verify", "/customer/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/cart/forgotpin/request", "/cart/forgotpin/reset").permitAll()
                        // Order-id-scoped status only: the tracking timeline and the notification audit trail are
                        // status + timestamps, and /summary (the logged-out "Track an order" box) additionally
                        // requires the matching phone number and returns no address or items.
                        .requestMatchers(HttpMethod.GET, "/cart/*/tracking", "/cart/*/notifications", "/cart/*/summary").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // The static dashboards themselves - just the HTML/JS shell; every call they make is
                        // authorized on its own.
                        .requestMatchers(HttpMethod.GET, "/cart.html", "/shop.html").permitAll()
                        // A controller-level failure (e.g. a missing required header) triggers an internal
                        // dispatch to /error; the auth filters don't re-run on that dispatch (OncePerRequestFilter
                        // skips ERROR dispatches by default), so without this the real error status gets clobbered
                        // by a spurious 401 from the unauthenticated /error request.
                        .requestMatchers("/error").permitAll()

                        // ---- Customer (own data only, see CustomerAccess) or service ----
                        .requestMatchers(HttpMethod.GET, "/customer/session").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/cart/byphno", "/cart/history", "/cart/notifications", "/cart/*/invoice", "/cart/*/paymentstatus",
                                "/wishlist/byphno", "/wishlist/pricedrops", "/waitlist/byphno", "/addresses/byphno",
                                "/loyalty/byphno", "/loyalty/history", "/customer/profile", "/coupons/available", "/referral/mine").hasAnyRole("CUSTOMER", "SERVICE")
                        // checkout/cancel/return still also need the buyer's own PhonePe credentials for anything
                        // that moves money (see OrderController) - the session only proves who the customer is.
                        .requestMatchers(HttpMethod.POST, "/cart/checkout", "/cart/reviews", "/cart/reviews/flag", "/cart/*/cancel", "/cart/*/return",
                                "/cart/*/items/*/cancel", "/cart/*/items/*/return",
                                "/wishlist/self/add", "/waitlist/self/add", "/addresses/self/add", "/referral/apply").hasAnyRole("CUSTOMER", "SERVICE")
                        .requestMatchers(HttpMethod.DELETE, "/wishlist/self/remove", "/waitlist/self/remove", "/addresses/self/remove").hasAnyRole("CUSTOMER", "SERVICE")

                        // ---- Service only ----
                        .anyRequest().hasRole("SERVICE"))
                // 401 for no/invalid credentials; a signed-in customer hitting a service-only endpoint gets the
                // default 403 from the access-denied handler.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ServiceKeyAuthenticationFilter(serviceApiKey), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CustomerTokenAuthenticationFilter(customerAuthService), ServiceKeyAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
