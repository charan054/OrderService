package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AccountDeletionPreview;
import com.example.orderservice.dto.AccountDeletionResult;
import com.example.orderservice.entity.AccountDeletionCode;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.Subscription;
import com.example.orderservice.exception.CustomerAuthException;
import com.example.orderservice.repository.AccountDeletionCodeRepository;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.SubscriptionRepository;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * "Delete my account", confirmed by a code emailed to the address the account is bound to. Three steps for the
 * storefront: preview (what would happen, and anything that blocks it), request a code, confirm with the code.
 * <p>
 * Deletion is refused while an order is still moving (awaiting payment, placed or shipped) or a delivered cash order has
 * not been marked paid - the shop would otherwise lose track of who owes what or whom to deliver to. Loyalty points and
 * store credit are lost with the account, so confirming needs acknowledgeForfeit=true when there are any. ProductService
 * (which owns reviews) is told first: if it cannot be reached nothing is erased and the customer can simply try again.
 * The actual erasure is AccountErasure, in one transaction.
 */
@Service
public class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    static final int MAX_ATTEMPTS = 5;
    private static final List<OrderStatus> IN_PROGRESS = List.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PLACED, OrderStatus.SHIPPED);

    private final CustomerAccountRepository accounts;
    private final AccountDeletionCodeRepository codes;
    private final CartRepository carts;
    private final LoyaltyAccountRepository loyalty;
    private final StoreCreditAccountRepository credit;
    private final SubscriptionRepository subscriptions;
    private final AccountErasure erasure;
    private final ProductClient productClient;
    private final MailService mailService;
    private final Clock clock;
    private final String serviceKey;
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final boolean logCodesWhenMailFails;
    private final SecureRandom random = new SecureRandom();

    public AccountDeletionService(CustomerAccountRepository accounts, AccountDeletionCodeRepository codes,
                                  CartRepository carts, LoyaltyAccountRepository loyalty,
                                  StoreCreditAccountRepository credit, SubscriptionRepository subscriptions,
                                  AccountErasure erasure, ProductClient productClient, MailService mailService,
                                  Clock clock,
                                  @Value("${internal.service.api-key}") String serviceKey,
                                  @Value("${account.deletion.code-ttl-minutes:10}") long codeTtlMinutes,
                                  @Value("${account.deletion.resend-cooldown-seconds:60}") long resendCooldownSeconds,
                                  @Value("${customer.login.log-codes-when-mail-fails:false}") boolean logCodesWhenMailFails) {
        this.accounts = accounts;
        this.codes = codes;
        this.carts = carts;
        this.loyalty = loyalty;
        this.credit = credit;
        this.subscriptions = subscriptions;
        this.erasure = erasure;
        this.productClient = productClient;
        this.mailService = mailService;
        this.clock = clock;
        this.serviceKey = serviceKey;
        this.codeTtl = Duration.ofMinutes(codeTtlMinutes);
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
        this.logCodesWhenMailFails = logCodesWhenMailFails;
    }

    public AccountDeletionPreview preview(long phno) {
        String email = accounts.findById(phno).map(CustomerAccount::getEmail).orElse(null);
        List<String> blockers = new ArrayList<>();
        long inProgress = carts.countByCustomerPhnoAndStatusIn(phno, IN_PROGRESS);
        if (inProgress > 0) {
            blockers.add(inProgress + " order(s) are still in progress (awaiting payment, placed or shipped). "
                    + "You can delete your account once they are delivered or cancelled.");
        }
        long unpaidCash = carts.countByCustomerPhnoAndStatusAndPaymentMethodAndPaidFalse(phno, OrderStatus.DELIVERED, PaymentMethod.CASH);
        if (unpaidCash > 0) {
            blockers.add(unpaidCash + " delivered cash-on-delivery order(s) have not been marked as paid yet.");
        }
        int points = loyalty.findById(phno).map(a -> a.getPointsBalance()).orElse(0);
        double balance = credit.findById(phno).map(a -> a.getBalance()).orElse(0.0);
        long activeSubscriptions = subscriptions.countByCustomerPhnoAndStatusIn(phno, List.of(Subscription.ACTIVE, Subscription.PAUSED));
        return new AccountDeletionPreview(email, blockers.isEmpty() && email != null, blockers, points, balance,
                activeSubscriptions, carts.countByCustomerPhno(phno));
    }

    /** Emails a fresh code to the address the account is bound to. */
    @Transactional
    public void requestCode(long phno) {
        AccountDeletionPreview preview = preview(phno);
        if (preview.email() == null) {
            throw new CustomerAuthException(HttpStatus.BAD_REQUEST, "There is no account to delete.");
        }
        if (!preview.canDelete()) {
            throw new CustomerAuthException(HttpStatus.CONFLICT, String.join(" ", preview.blockers()));
        }
        Instant now = clock.instant();
        codes.deleteByExpiresAtBefore(now);
        Optional<AccountDeletionCode> pending = codes.findByPhno(phno);
        if (pending.isPresent() && pending.get().getCreatedAt().plus(resendCooldown).isAfter(now)) {
            throw new CustomerAuthException(HttpStatus.TOO_MANY_REQUESTS,
                    "A code was just sent. Wait a minute before asking for another.");
        }
        pending.ifPresent(codes::delete);
        codes.flush();

        String code = String.format("%06d", random.nextInt(1_000_000));
        AccountDeletionCode row = new AccountDeletionCode();
        row.setPhno(phno);
        row.setCodeHash(hash(code));
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(codeTtl));
        codes.save(row);

        boolean sent = mailService.send(preview.email(), "Confirm deleting your account",
                "Someone asked to delete the account for the mobile number ending " + lastFour(phno) + ".\n\n"
                        + "Your confirmation code is " + code + ". It expires in " + codeTtl.toMinutes() + " minutes.\n\n"
                        + "Deleting the account permanently removes your saved addresses, wishlist, alerts, saved cart, "
                        + "subscriptions, loyalty points and store credit, and your email is unlinked. Past orders are kept "
                        + "for invoices and tax records without your name or phone number.\n\n"
                        + "If you didn't ask for this, ignore this email - nothing will be deleted.");
        if (!sent && logCodesWhenMailFails) {
            // Local development only (customer.login.log-codes-when-mail-fails) - never enable where real customers sign in.
            log.warn("DEV ONLY: account deletion code for {} is {}", phno, code);
        }
    }

    /**
     * Checks the code and, if everything is in order, erases the account. Deliberately not @Transactional: a wrong guess
     * must stay counted even though the call then fails.
     */
    public AccountDeletionResult confirm(long phno, String code, boolean acknowledgeForfeit) {
        AccountDeletionCode row = codes.findByPhno(phno).orElseThrow(
                () -> new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code. Ask for a new one."));
        if (!row.getExpiresAt().isAfter(clock.instant())) {
            codes.delete(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code. Ask for a new one.");
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            codes.delete(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Too many incorrect attempts. Ask for a new code.");
        }
        if (code == null || !MessageDigest.isEqual(row.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(code.trim()).getBytes(StandardCharsets.UTF_8))) {
            row.setAttempts(row.getAttempts() + 1);
            codes.save(row);
            throw new CustomerAuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired code. Ask for a new one.");
        }

        // Re-checked now, not trusted from when the code was requested: an order may have been placed since.
        AccountDeletionPreview preview = preview(phno);
        if (!preview.canDelete()) {
            throw new CustomerAuthException(HttpStatus.CONFLICT, String.join(" ", preview.blockers()));
        }
        if ((preview.loyaltyPoints() > 0 || preview.storeCredit() > 0) && !acknowledgeForfeit) {
            throw new CustomerAuthException(HttpStatus.BAD_REQUEST, "You still have " + preview.loyaltyPoints()
                    + " loyalty point(s) and store credit of " + preview.storeCredit()
                    + ". They are lost when the account is deleted - confirm that you accept this.");
        }

        int reviews;
        try {
            reviews = productClient.anonymiseReviews(serviceKey, phno);
        } catch (RuntimeException e) {
            // Nothing has been erased yet and the code is still valid, so the customer can just try again.
            throw new CustomerAuthException(HttpStatus.BAD_GATEWAY,
                    "We couldn't reach the product service to remove your name from your reviews. Nothing was deleted - please try again in a moment.");
        }
        AccountDeletionResult result = erasure.erase(phno, reviews);

        // Best effort: the account is already gone, so a mail failure must not turn the result into an error.
        try {
            mailService.send(preview.email(), "Your account has been deleted",
                    "The account for the mobile number ending " + lastFour(phno) + " has been deleted and this email address "
                            + "is no longer linked to it. If you did not do this, please contact the shop right away.");
        } catch (RuntimeException e) {
            log.warn("Could not send the account-deleted notice: {}", e.getMessage());
        }
        return result;
    }

    private static String lastFour(long phno) {
        String digits = String.valueOf(phno);
        return digits.substring(Math.max(0, digits.length() - 4));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
