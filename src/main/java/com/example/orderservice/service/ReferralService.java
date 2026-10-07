package com.example.orderservice.service;

import com.example.orderservice.dto.ReferralInfo;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.Referral;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.ReferralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Refer-a-friend. Every customer with a verified account has a personal code. A NEW customer (no orders yet) can
 * apply one friend's code once; when their first qualifying order is delivered, both get bonus loyalty points.
 *
 * Guard rails, because points are worth money here: the friend must be new, can be referred only once, may not be
 * the same person as the referrer (same verified email), the order must be worth at least referral.min-order-amount
 * net of refunds, and one referrer can be rewarded at most referral.max-rewards-per-referrer times. The reward is
 * marked paid BEFORE the points are credited, so a retry can never pay twice.
 */
@Service
public class ReferralService {
    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);
    // No 0/O, 1/I/L - easy to read out over the phone.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final CustomerAccountRepository customers;
    private final ReferralRepository referrals;
    private final CartRepository orders;
    private final OrderService orderService;
    private final MailService mailService;
    private final Clock clock;
    private final int bonusPoints;
    private final double minOrderAmount;
    private final int maxRewardsPerReferrer;
    private final SecureRandom random = new SecureRandom();

    public ReferralService(CustomerAccountRepository customers, ReferralRepository referrals, CartRepository orders,
                           OrderService orderService, MailService mailService, Clock clock,
                           @Value("${referral.bonus-points:100}") int bonusPoints,
                           @Value("${referral.min-order-amount:200}") double minOrderAmount,
                           @Value("${referral.max-rewards-per-referrer:10}") int maxRewardsPerReferrer) {
        this.customers = customers;
        this.referrals = referrals;
        this.orders = orders;
        this.orderService = orderService;
        this.mailService = mailService;
        this.clock = clock;
        this.bonusPoints = bonusPoints;
        this.minOrderAmount = minOrderAmount;
        this.maxRewardsPerReferrer = maxRewardsPerReferrer;
    }

    // The customer's own referral card; the code is created the first time it is asked for.
    public ReferralInfo getInfo(long phno) {
        CustomerAccount account = requireAccount(phno);
        if (account.getReferralCode() == null) {
            account.setReferralCode(newUniqueCode());
            customers.save(account);
        }
        List<Referral> mine = referrals.findByReferrerPhno(phno);
        List<Referral> rewarded = mine.stream().filter(Referral::isRewarded).toList();
        int earned = rewarded.stream().mapToInt(Referral::getBonusPoints).sum();
        return new ReferralInfo(account.getReferralCode(), mine.size(), rewarded.size(), earned, bonusPoints,
                minOrderAmount, referrals.findByRefereePhno(phno).isPresent());
    }

    public void apply(long phno, String code) {
        if (code == null || code.isBlank()) {
            throw new ProductException("Enter a referral code");
        }
        CustomerAccount referee = requireAccount(phno);
        CustomerAccount referrer = customers.findByReferralCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ProductException("That referral code doesn't exist"));
        if (referrer.getPhno() == phno
                || (referrer.getEmail() != null && referrer.getEmail().equalsIgnoreCase(referee.getEmail()))) {
            throw new ProductException("You can't use your own referral code");
        }
        if (referrals.findByRefereePhno(phno).isPresent()) {
            throw new ProductException("You have already used a referral code");
        }
        if (!orders.findBycustomerPhno(phno).isEmpty()) {
            throw new ProductException("Referral codes are for new customers - you have already placed an order");
        }
        if (referrals.countByReferrerPhnoAndRewardedTrue(referrer.getPhno()) >= maxRewardsPerReferrer) {
            throw new ProductException("That referral code has reached its limit");
        }
        Referral referral = new Referral();
        referral.setReferrerPhno(referrer.getPhno());
        referral.setRefereePhno(phno);
        referral.setCreatedAt(clock.instant());
        referrals.save(referral);
    }

    // Pays the bonus when a referred friend's first qualifying order is delivered. Anything that goes wrong is
    // logged, never propagated: it must not turn a successful delivery into an error.
    @EventListener
    public void onOrderDelivered(OrderDeliveredEvent event) {
        try {
            reward(event.order());
        } catch (RuntimeException e) {
            log.error("Referral reward for order {} failed: {}", event.order().getOrderId(), e.getMessage(), e);
        }
    }

    void reward(Cart order) {
        Referral referral = referrals.findByRefereePhno(order.getCustomerPhno()).orElse(null);
        if (referral == null || referral.isRewarded()) {
            return;
        }
        if (order.getTotalPrice() - order.getRefundedAmount() < minOrderAmount) {
            return; // too small to count - a later, bigger delivered order can still qualify
        }
        if (referrals.countByReferrerPhnoAndRewardedTrue(referral.getReferrerPhno()) >= maxRewardsPerReferrer) {
            log.info("Referral {} not rewarded: referrer {} reached the limit", referral.getId(), referral.getReferrerPhno());
            return;
        }
        // Paid-first: if crediting fails below, the reward is lost (and logged) rather than ever paid twice.
        referral.setRewarded(true);
        referral.setRewardedAt(clock.instant());
        referral.setRewardOrderId(order.getOrderId());
        referral.setBonusPoints(bonusPoints);
        referrals.save(referral);

        orderService.adjustLoyaltyPoints(referral.getReferrerPhno(), bonusPoints,
                "Referral bonus: a friend you referred received their first order");
        orderService.adjustLoyaltyPoints(referral.getRefereePhno(), bonusPoints,
                "Referral bonus: welcome bonus for using a friend's code");
        email(referral.getReferrerPhno(), "You earned " + bonusPoints + " referral points",
                "Hi,\n\nA friend you referred just received their first order, so we've added " + bonusPoints
                        + " loyalty points to your account (1 point = Rs. 1). Thanks for spreading the word!\n");
        email(referral.getRefereePhno(), "Your " + bonusPoints + " welcome points are here",
                "Hi,\n\nThanks for your first order! Because you used a friend's referral code we've added " + bonusPoints
                        + " loyalty points to your account (1 point = Rs. 1) - use them at checkout.\n");
    }

    private void email(long phno, String subject, String body) {
        customers.findById(phno).map(CustomerAccount::getEmail).filter(e -> !e.isBlank())
                .ifPresent(address -> mailService.send(address, subject, body));
    }

    private CustomerAccount requireAccount(long phno) {
        return customers.findById(phno)
                .orElseThrow(() -> new ProductException("Sign in to the shop with your email first - referral codes belong to verified accounts"));
    }

    private String newUniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (customers.findByReferralCode(code.toString()).isEmpty()) {
                return code.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique referral code");
    }
}
