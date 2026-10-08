package com.example.orderservice.controller;

import com.example.orderservice.dto.AdminAccountView;
import com.example.orderservice.dto.AdminConfig;
import com.example.orderservice.dto.AdminIdentity;
import com.example.orderservice.dto.AdminLoginRequest;
import com.example.orderservice.dto.AdminLoginResponse;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.dto.PasswordChange;
import com.example.orderservice.entity.AdminLoginEvent;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.exception.AdminAuthException;
import com.example.orderservice.security.AdminPrincipal;
import com.example.orderservice.security.AdminTokenAuthenticationFilter;
import com.example.orderservice.security.LoginRateLimiter;
import com.example.orderservice.security.NamedLoginPolicy;
import com.example.orderservice.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Named admin accounts. Login and logout are public (you have no session yet); /me needs any signed-in admin; the
 * account-management endpoints are for an OWNER or the service key (AdminPolicy keeps everything else under /admin
 * owner-only). Passwords only ever travel in request bodies, never in the URL, since the audit log records paths.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final String SERVICE_KEY_ACTOR = "service-key";

    private final AdminAuthService adminAuthService;
    private final LoginRateLimiter loginRateLimiter;
    private final NamedLoginPolicy namedLoginPolicy;

    public AdminController(AdminAuthService adminAuthService, LoginRateLimiter loginRateLimiter,
                           NamedLoginPolicy namedLoginPolicy) {
        this.adminAuthService = adminAuthService;
        this.loginRateLimiter = loginRateLimiter;
        this.namedLoginPolicy = namedLoginPolicy;
    }

    @PostMapping("/login")
    public AdminLoginResponse login(@RequestBody AdminLoginRequest request, HttpServletRequest http) {
        loginRateLimiter.checkAdminLogin(http.getRemoteAddr(), request.username());
        return adminAuthService.login(request.username(), request.password(), http.getRemoteAddr());
    }

    // Public: the dashboard asks before sign-in whether the shared service key is still accepted from a browser.
    @GetMapping("/config")
    public AdminConfig config() {
        return new AdminConfig(namedLoginPolicy.required());
    }

    // Owners: who signed in (or tried to) and from where, newest first; the last 90 days.
    @GetMapping("/logins")
    public List<AdminLoginEvent> logins(@RequestParam(defaultValue = "100") int limit,
                                        @RequestParam(required = false) String username) {
        return adminAuthService.recentLogins(username, limit);
    }

    // Public like the customer logout: it only ever ends the caller's own token, and an expired one can still log out.
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = AdminTokenAuthenticationFilter.HEADER, required = false) String token) {
        adminAuthService.logout(token);
    }

    // Who the dashboard is acting as - it calls this on load to check a saved admin token still works.
    @GetMapping("/me")
    public AdminIdentity me(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal admin) {
            return new AdminIdentity(admin.username(), admin.role(), false);
        }
        return new AdminIdentity(SERVICE_KEY_ACTOR, AdminRole.OWNER, true);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOwnPassword(@RequestBody PasswordChange change, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AdminPrincipal admin)) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "The service key has no password. Sign in as a named admin.");
        }
        adminAuthService.changeOwnPassword(admin.username(), change.currentPassword(), change.newPassword());
    }

    @GetMapping("/accounts")
    public List<AdminAccountView> accounts() {
        return adminAuthService.list();
    }

    @PostMapping("/accounts")
    public AdminAccountView createAccount(@RequestBody NewAdminAccount request, Authentication authentication) {
        return adminAuthService.create(request, actor(authentication));
    }

    @DeleteMapping("/accounts/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable String username, Authentication authentication) {
        adminAuthService.delete(username, actor(authentication));
    }

    @PutMapping("/accounts/{username}/role")
    public AdminAccountView setRole(@PathVariable String username, @RequestParam AdminRole role) {
        return adminAuthService.setRole(username, role);
    }

    @PutMapping("/accounts/{username}/active")
    public AdminAccountView setActive(@PathVariable String username, @RequestParam boolean active) {
        return adminAuthService.setActive(username, active);
    }

    // An owner sets someone's password without the old one (forgotten password); currentPassword is ignored.
    @PutMapping("/accounts/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String username, @RequestBody PasswordChange change) {
        adminAuthService.resetPassword(username, change.newPassword());
    }

    private static String actor(Authentication authentication) {
        return authentication.getPrincipal() instanceof AdminPrincipal admin ? admin.username() : SERVICE_KEY_ACTOR;
    }
}
