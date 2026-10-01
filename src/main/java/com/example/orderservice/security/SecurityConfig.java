package com.example.orderservice.security;

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
 * Browsing the catalog through /cart/display and looking up one customer's own orders by phone stay public;
 * placing an order, removing an item, and listing EVERY customer's orders (/cart/all, which otherwise hands
 * anyone the full order history of every customer) require a valid X-Service-Key - the same trust boundary
 * Bankapplication and PhonepayService already enforce for their own service-to-service calls.
 */
@Configuration
public class SecurityConfig {

    private final String serviceApiKey;

    public SecurityConfig(@Value("${internal.service.api-key}") String serviceApiKey) {
        this.serviceApiKey = serviceApiKey;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/cart/display", "/cart/byphno", "/cart/frequentlyboughttogether", "/cart/search", "/cart/ratings", "/cart/reviews", "/cart/gallery").permitAll()
                        // Posting a review is a customer action, same self-service trust level as the storefront's
                        // own checkout/wishlist/address writes above - proxies straight to ProductService's own
                        // public review-posting endpoint.
                        .requestMatchers(HttpMethod.POST, "/cart/reviews").permitAll()
                        // The customer-facing storefront's own checkout - see OrderController.checkout for why
                        // this can't require the same X-Service-Key /cart/add does.
                        .requestMatchers(HttpMethod.POST, "/cart/checkout").permitAll()
                        // A customer cancelling their own order - same reasoning as checkout above. The only gate
                        // is that a PHONEPE refund requires the real buyer token (see OrderController.cancelOrder);
                        // a CASH order has no gate at all, same trust level the storefront's other self-service
                        // writes already have.
                        .requestMatchers(HttpMethod.POST, "/cart/*/cancel").permitAll()
                        // Same reasoning as cancel above - a customer requesting a return on their own delivered
                        // order. The only gate is the same PHONEPE-refund buyer-token requirement.
                        .requestMatchers(HttpMethod.POST, "/cart/*/return").permitAll()
                        // Same self-service trust level as /cart/byphno above - looking up your own wishlist (and
                        // its price-drop alerts) by your own phone number.
                        .requestMatchers(HttpMethod.GET, "/wishlist/byphno", "/wishlist/pricedrops").permitAll()
                        // The storefront's own add/remove of a customer's own wishlist entries - see
                        // WishlistController.addToOwnWishlist/removeFromOwnWishlist for why these don't need the
                        // same X-Service-Key /wishlist/add and /remove do.
                        .requestMatchers(HttpMethod.POST, "/wishlist/self/add").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/wishlist/self/remove").permitAll()
                        // The back-in-stock waitlist is a purely self-service feature - no admin/dashboard use
                        // case exists, so unlike Wishlist there's no separate X-Service-Key-gated pair.
                        .requestMatchers(HttpMethod.GET, "/waitlist/byphno").permitAll()
                        .requestMatchers(HttpMethod.POST, "/waitlist/self/add").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/waitlist/self/remove").permitAll()
                        // Polled by the storefront while a UPI-collect order sits PENDING_PAYMENT - same public,
                        // self-service trust level as tracking below (just the status of your own order).
                        .requestMatchers(HttpMethod.GET, "/cart/*/paymentstatus").permitAll()
                        // The tracking timeline is just a per-transition history of the same status field
                        // /cart/byphno already returns for every order - no additional exposure.
                        .requestMatchers(HttpMethod.GET, "/cart/*/tracking").permitAll()
                        // The notification audit trail is derived from the same status field as tracking above -
                        // same public trust level. /cart/notifications (no order id) backs the storefront's "My
                        // notifications" panel - same self-service trust level as /cart/byphno.
                        .requestMatchers(HttpMethod.GET, "/cart/*/notifications", "/cart/notifications").permitAll()
                        // Same self-service trust level as /cart/byphno above - looking up your own saved
                        // addresses by your own phone number.
                        .requestMatchers(HttpMethod.GET, "/addresses/byphno").permitAll()
                        // The storefront's own add/remove of a customer's own saved addresses - see
                        // ShippingAddressController.addOwnAddress/removeOwnAddress.
                        .requestMatchers(HttpMethod.POST, "/addresses/self/add").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/addresses/self/remove").permitAll()
                        // Same self-service trust level as /cart/byphno above - looking up your own loyalty
                        // points balance/history by your own phone number.
                        .requestMatchers(HttpMethod.GET, "/loyalty/byphno", "/loyalty/history").permitAll()
                        // Same self-service trust level as /cart/byphno above - a rollup of your own
                        // orders/wishlist/loyalty/review data by your own phone number.
                        .requestMatchers(HttpMethod.GET, "/customer/profile").permitAll()
                        // Note: /cart/analytics is deliberately NOT in this permitAll list - it's an admin-only
                        // sales rollup (see OrderController.getSalesAnalytics), so it falls through to
                        // anyRequest().authenticated() below like /cart/all does.
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // The static dashboard itself - not an order action, just the HTML/JS shell. The
                        // mutating buttons on it still hit the X-Service-Key-guarded endpoints above like any
                        // other caller, so this only unblocks loading the page, not bypassing anything.
                        .requestMatchers(HttpMethod.GET, "/cart.html", "/shop.html").permitAll()
                        // A controller-level failure (e.g. a missing required header) triggers an internal
                        // dispatch to /error; ServiceKeyAuthenticationFilter doesn't re-run on that dispatch
                        // (OncePerRequestFilter skips ERROR dispatches by default), so without this the real
                        // error status gets clobbered by a spurious 401 from the unauthenticated /error request.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ServiceKeyAuthenticationFilter(serviceApiKey), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
