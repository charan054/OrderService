package com.example.orderservice.service;

import com.example.orderservice.dto.CustomerLoginResponse;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.CustomerLoginCode;
import com.example.orderservice.entity.CustomerSession;
import com.example.orderservice.exception.CustomerAuthException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.CustomerLoginCodeRepository;
import com.example.orderservice.repository.CustomerSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Storefront login by emailed one-time code. Same shape as Bankapplication's PinResetService (random secret,
 * stored only as a SHA-256 hash, short expiry, capped wrong guesses), then a random session token - also stored
 * only as a hash - that shop.html sends as X-Customer-Token. No scheduler exists in this service, so expired rows
 * are purged lazily each time a new code is requested.
 */
@Service
public class CustomerAuthService {
    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);
    static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final CustomerAccountRepository accounts;
    private final CustomerLoginCodeRepository codes;
    private final CustomerSessionRepository sessions;
    private final MailService mailService;
    private final Clock clock;
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final Duration sessionTtl;
    private final boolean logCodesWhenMailFails;
    private final SecureRandom random = new SecureRandom();

    public CustomerAuthService(CustomerAccountRepository accounts, CustomerLoginCodeRepository codes,
                               CustomerSessionRepository sessions, MailService mailService, Clock clock,
                               @Value("${customer.login.code-ttl-minutes:10}") long codeTtlMinutes,
                               @Value("${customer.login.resend-cooldown-seconds:60}") long resendCooldownSeconds,
                               @Value("${customer.login.session-ttl-days:30}") long sessionTtlDays,
                               @Value("${customer.login.log-codes-when-mail-fails:false}") boolean logCodesWhenMailFails) {
        this.accounts = accounts;
        this.codes = codes;
        this.sessions = sessions;
        this.mailService = mailService;
        this.clock = clock;
        this.codeTtl = Duration.ofMinutes(codeTtlMinutes);
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
        this.sessionTtl = Duration.ofDays(sessionTtlDays);
        this.logCodesWhenMailFails = logCodesWhenMailFails;
    }

    /**
     * The response is the same whether or not this phone already has an email bound - so it can't be used to
     * discover which numbers are registered. Once bound, the typed email is ignored and the code goes only to the
     * bound address, so typing someone else's number with your own email gets you nothing.
     */
    @Transactional
    public void requestCode(long phno, String email) {
        validatePhno(phno);
        String typedEmail = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL.matcher(typedEmail).matches()) {
            throw new CustomerAuthException(HttpStatus.BAD_REQUEST, "Enter a valid email address.");
        }

        Instant now = clock.instant();
        codes.deleteByExpiresAtBefore(now);
        sessions.deleteByExpiresAtBefore(now);

        Optional<CustomerLoginCode> pending = codes.findByPhno(phno);
        if (pending.isPresent() && pending.get().getCreatedAt().plus(resendCooldown).isAfter(now)) {
            throw new CustomerAuthException(HttpStatus.TOO_MANY_REQUESTS,
                    "A code was just sent. Wait a minute before asking for another.");
        }
        pending.ifPresent(codes::delete);
        codes.flush();

        String target = accounts.findById(phno).map(CustomerAccount::getEmail).orElse(typedEmail);
        String code = String.format("%06d", random.nextInt(1_000_000));

        CustomerLoginCode row = new CustomerLoginCode();
        row.setPhno(phno);
        row.setCodeHash(hash(code));
        row.setEmail(target);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(codeTtl));
        codes.save(row);

        boolean sent = mailService.send(target, "Your sign-in code",
                "Your sign-in code is " + code + ". It expires in " + codeTtl.toMinutes()
                        + " minutes. If you didn't try to sign in, you can ignore this email.");
        if (!sent && logCodesWhenMailFails) {
            // Local development only (customer.login.log-codes-when-mail-fails) - never enable where real
            // customers sign in, since anyone with log access could then sign in as them.
            log.warn("DEV ONLY: sign-in code for {} is {}", phno, code);
        }
    }

    @Transactional(noRollbackFor = CustomerAuthException.class)
    public CustomerLoginResponse verifyCode(long phno, String code) {
        CustomerLoginCode row = codes.findByPhno(phno).orElseThrow(
                () -> new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code."));
        if (!row.getExpiresAt().isAfter(clock.instant())) {
            codes.delete(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code.");
        }
        if (row.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            codes.delete(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Too many incorrect attempts. Request a new code.");
        }
        if (code == null || !MessageDigest.isEqual(row.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(code.trim()).getBytes(StandardCharsets.UTF_8))) {
            row.setAttempts(row.getAttempts() + 1);
            codes.save(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code.");
        }
        codes.delete(row);

        // First verified email claims the phone number; later codes were already sent only to the bound address.
        if (accounts.findById(phno).isEmpty()) {
            CustomerAccount account = new CustomerAccount();
            account.setPhno(phno);
            account.setEmail(row.getEmail());
            account.setVerifiedAt(clock.instant());
            accounts.save(account);
        }
        return issueSession(phno);
    }

    // Public so tests (and nothing else) can get a session without going through email.
    @Transactional
    public CustomerLoginResponse issueSession(long phno) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();

        CustomerSession session = new CustomerSession();
        session.setTokenHash(hash(token));
        session.setPhno(phno);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        sessions.save(session);
        return new CustomerLoginResponse(token, phno, session.getExpiresAt());
    }

    /** The phone number a still-valid token belongs to, or empty for an unknown/expired one. */
    @Transactional
    public Optional<Long> resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<CustomerSession> session = sessions.findByTokenHash(hash(token));
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (!session.get().getExpiresAt().isAfter(clock.instant())) {
            sessions.delete(session.get());
            return Optional.empty();
        }
        return Optional.of(session.get().getPhno());
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteByTokenHash(hash(token));
        }
    }

    public Optional<String> boundEmail(long phno) {
        return accounts.findById(phno).map(CustomerAccount::getEmail);
    }

    /**
     * Admin recovery (X-Service-Key): rebinds a phone to a new email after the customer's identity was checked out
     * of band - the only way to undo someone else having claimed a number first. Signs out every existing session
     * and drops any pending code, since those belonged to whoever held the old email.
     */
    @Transactional
    public void adminSetEmail(long phno, String email) {
        validatePhno(phno);
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL.matcher(normalized).matches()) {
            throw new CustomerAuthException(HttpStatus.BAD_REQUEST, "Enter a valid email address.");
        }
        CustomerAccount account = accounts.findById(phno).orElseGet(CustomerAccount::new);
        account.setPhno(phno);
        account.setEmail(normalized);
        account.setVerifiedAt(clock.instant());
        accounts.save(account);
        sessions.deleteByPhno(phno);
        codes.deleteByPhno(phno);
    }

    private void validatePhno(long phno) {
        String x = "" + phno;
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
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
