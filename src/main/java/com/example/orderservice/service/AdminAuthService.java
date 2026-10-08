package com.example.orderservice.service;

import com.example.orderservice.dto.AdminAccountView;
import com.example.orderservice.dto.AdminLoginResponse;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.entity.AdminSession;
import com.example.orderservice.exception.AdminAuthException;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.security.AdminPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Named admin accounts for the dashboard, so each change can be traced to a person (see AuditLogFilter) and a
 * support agent need not hold the shared service key. Passwords are stored as BCrypt hashes; a session token is
 * random, handed out once, and stored only as a SHA-256 hash - the same shape as CustomerAuthService. The role is
 * read from the account on every request, so demoting or disabling someone takes effect immediately.
 * The service key itself is untouched: it stays the credential for other services and the way back in if every
 * owner is locked out.
 */
@Service
public class AdminAuthService {
    static final int MIN_PASSWORD_LENGTH = 10;
    // BCrypt only looks at the first 72 bytes, so anything longer would silently be a shorter password.
    static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");
    private static final String INVALID_LOGIN = "Invalid username or password.";

    private final AdminAccountRepository accounts;
    private final AdminSessionRepository sessions;
    private final Clock clock;
    private final Duration sessionTtl;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    // Checked against when the username doesn't exist, so an unknown name costs the same as a wrong password.
    private final String dummyHash = encoder.encode("timing-equaliser-not-a-real-password");

    public AdminAuthService(AdminAccountRepository accounts, AdminSessionRepository sessions, Clock clock,
                            @Value("${admin.session-ttl-hours:12}") long sessionTtlHours) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
    }

    @Transactional
    public AdminLoginResponse login(String username, String password) {
        Optional<AdminAccount> found = accounts.findByUsername(normalize(username));
        // Always hash once, against the real hash or the dummy, so timing doesn't reveal which usernames exist. An
        // over-long password is never a match (it can't have been set, see requireStrongPassword).
        String candidate = fitsBcrypt(password) ? password : "";
        boolean passwordOk = encoder.matches(candidate, found.map(AdminAccount::getPasswordHash).orElse(dummyHash))
                && fitsBcrypt(password);
        if (found.isEmpty() || !passwordOk || !found.get().isActive()) {
            throw new AdminAuthException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        AdminAccount account = found.get();
        Instant now = clock.instant();
        sessions.deleteByExpiresAtBefore(now);
        account.setLastLoginAt(now);
        accounts.save(account);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AdminSession session = new AdminSession();
        session.setTokenHash(hash(token));
        session.setAdminId(account.getId());
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        sessions.save(session);
        return new AdminLoginResponse(token, account.getUsername(), account.getRole(), session.getExpiresAt());
    }

    /** Who a still-valid token belongs to, or empty for an unknown/expired token or a disabled account. */
    @Transactional
    public Optional<AdminPrincipal> resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<AdminSession> session = sessions.findByTokenHash(hash(token));
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (!session.get().getExpiresAt().isAfter(clock.instant())) {
            sessions.delete(session.get());
            return Optional.empty();
        }
        return accounts.findById(session.get().getAdminId())
                .filter(AdminAccount::isActive)
                .map(a -> new AdminPrincipal(a.getUsername(), a.getRole()));
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteByTokenHash(hash(token));
        }
    }

    @Transactional
    public AdminAccountView create(NewAdminAccount request, String createdBy) {
        String username = normalize(request.username());
        if (!USERNAME.matcher(username).matches()) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST,
                    "Username must be 3-32 characters: lowercase letters, digits, dots, dashes or underscores, starting with a letter or digit.");
        }
        if (request.role() == null) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "Choose a role: SUPPORT, MANAGER or OWNER.");
        }
        requireStrongPassword(request.password());
        if (accounts.findByUsername(username).isPresent()) {
            throw new AdminAuthException(HttpStatus.CONFLICT, "That username is already taken.");
        }
        AdminAccount account = new AdminAccount();
        account.setUsername(username);
        account.setPasswordHash(encoder.encode(request.password()));
        account.setRole(request.role());
        account.setCreatedAt(clock.instant());
        account.setCreatedBy(createdBy);
        return AdminAccountView.of(accounts.save(account));
    }

    @Transactional(readOnly = true)
    public List<AdminAccountView> list() {
        return accounts.findAllByOrderByUsernameAsc().stream().map(AdminAccountView::of).toList();
    }

    @Transactional
    public AdminAccountView setRole(String username, AdminRole role) {
        if (role == null) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "Choose a role: SUPPORT, MANAGER or OWNER.");
        }
        AdminAccount account = require(username);
        if (account.getRole() == AdminRole.OWNER && role != AdminRole.OWNER && account.isActive()) {
            requireAnotherActiveOwner();
        }
        account.setRole(role);
        return AdminAccountView.of(accounts.save(account));
    }

    @Transactional
    public AdminAccountView setActive(String username, boolean active) {
        AdminAccount account = require(username);
        if (!active && account.isActive()) {
            if (account.getRole() == AdminRole.OWNER) {
                requireAnotherActiveOwner();
            }
            sessions.deleteByAdminId(account.getId());
        }
        account.setActive(active);
        return AdminAccountView.of(accounts.save(account));
    }

    /** An owner (or the service key) sets someone's password without knowing the old one; their sessions end. */
    @Transactional
    public void resetPassword(String username, String newPassword) {
        requireStrongPassword(newPassword);
        AdminAccount account = require(username);
        account.setPasswordHash(encoder.encode(newPassword));
        accounts.save(account);
        sessions.deleteByAdminId(account.getId());
    }

    /** Changing your own password needs the current one; every session of the account ends, so sign in again. */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        AdminAccount account = require(username);
        if (!fitsBcrypt(currentPassword) || !encoder.matches(currentPassword, account.getPasswordHash())) {
            throw new AdminAuthException(HttpStatus.FORBIDDEN, "Your current password is not right.");
        }
        resetPassword(username, newPassword);
    }

    private void requireAnotherActiveOwner() {
        if (accounts.countByRoleAndActiveTrue(AdminRole.OWNER) <= 1) {
            throw new AdminAuthException(HttpStatus.CONFLICT, "Keep at least one active owner account.");
        }
    }

    private AdminAccount require(String username) {
        return accounts.findByUsername(normalize(username))
                .orElseThrow(() -> new AdminAuthException(HttpStatus.NOT_FOUND, "No such admin account."));
    }

    private void requireStrongPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (!fitsBcrypt(password)) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "Password is too long (at most " + MAX_PASSWORD_BYTES + " bytes).");
        }
        if (password.isBlank()) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, "Password can't be blank.");
        }
    }

    private static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
