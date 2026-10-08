package com.example.orderservice.security;

import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.repository.AdminAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optionally retires the shared service key from the browser dashboard (admin.require-named-login). When on, a
 * request that comes from a browser - it carries Origin or Sec-Fetch-Site, which a page cannot omit or forge - is not
 * accepted on the strength of X-Service-Key, so everyone using cart.html has to sign in as themselves and every
 * change is attributable. Scripts and other services, which send neither header, keep using the key.
 * That is an accountability control, not a boundary: anyone holding the key can still call the API without browser
 * headers. It is also ignored until at least one active OWNER account exists, so switching it on before anyone can
 * sign in can't lock the dashboard out.
 */
@Component
public class NamedLoginPolicy {
    private final AdminAccountRepository accounts;
    private final boolean enabled;

    public NamedLoginPolicy(AdminAccountRepository accounts,
                            @Value("${admin.require-named-login:false}") boolean enabled) {
        this.accounts = accounts;
        this.enabled = enabled;
    }

    /** True when browser dashboards must sign in with a named account. */
    public boolean required() {
        return enabled && accounts.countByRoleAndActiveTrue(AdminRole.OWNER) > 0;
    }

    /** True when this request must not be authenticated by the service key. */
    public boolean refusesServiceKey(HttpServletRequest request) {
        return fromBrowser(request) && required();
    }

    static boolean fromBrowser(HttpServletRequest request) {
        return request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null;
    }
}
