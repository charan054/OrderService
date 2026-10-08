package com.example.orderservice.service;

import com.example.orderservice.dto.GiftCardDtos.GiftCardList;
import com.example.orderservice.dto.GiftCardDtos.GiftCardView;
import com.example.orderservice.dto.GiftCardDtos.MintRequest;
import com.example.orderservice.dto.GiftCardDtos.MintResult;
import com.example.orderservice.dto.GiftCardDtos.MintedCard;
import com.example.orderservice.dto.GiftCardDtos.RedeemResult;
import com.example.orderservice.entity.GiftCard;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.GiftCardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
import java.util.Locale;
import java.util.Set;

/**
 * Gift cards: an owner mints one-time codes worth a fixed amount, a customer redeems one into their store credit
 * (see StoreCreditService), and the wallet then works as it always does. A code is 16 random characters from a
 * 31-letter alphabet (about 79 bits - not guessable), written GC-XXXX-XXXX-XXXX-XXXX; entering it is forgiving about
 * case, spaces and dashes. Redeeming is a single conditional UPDATE (GiftCardRepository.claim) in the same
 * transaction as the credit, so a card can be used exactly once even if two people submit it at the same moment.
 */
@Service
public class GiftCardService {
    static final int MAX_BATCH = 100;
    static final int MAX_LIST = 500;
    static final int MAX_EXPIRY_DAYS = 1825;
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 16;
    private static final String PREFIX = "GC";
    private static final Set<String> STATUSES = Set.of("ACTIVE", "REDEEMED", "EXPIRED", "VOIDED");

    private final GiftCardRepository cards;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final double maxAmount;
    private final int defaultExpiryDays;

    @Autowired
    private StoreCreditService storeCreditService;

    public GiftCardService(GiftCardRepository cards, Clock clock,
                           @Value("${gift-card.max-amount:10000}") double maxAmount,
                           @Value("${gift-card.default-expiry-days:365}") int defaultExpiryDays) {
        this.cards = cards;
        this.clock = clock;
        this.maxAmount = maxAmount;
        this.defaultExpiryDays = defaultExpiryDays;
    }

    @Transactional
    public MintResult mint(MintRequest request, String actor) {
        if (request.count() < 1 || request.count() > MAX_BATCH) {
            throw new ProductException("Mint between 1 and " + MAX_BATCH + " cards at a time");
        }
        if (!Double.isFinite(request.amount())) {
            throw new ProductException("Amount must be between Rs. 1 and Rs. " + (long) maxAmount);
        }
        double amount = StoreCreditService.money(request.amount());
        if (amount < 1 || amount > maxAmount) {
            throw new ProductException("Amount must be between Rs. 1 and Rs. " + (long) maxAmount);
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 100) {
            throw new ProductException("Note must be at most 100 characters");
        }
        int days = request.expiresInDays() == null ? defaultExpiryDays : request.expiresInDays();
        if (days < 0 || days > MAX_EXPIRY_DAYS) {
            throw new ProductException("Expiry must be 0 (never) to " + MAX_EXPIRY_DAYS + " days");
        }
        Instant now = clock.instant();
        Instant expiresAt = days == 0 ? null : now.plus(Duration.ofDays(days));

        List<MintedCard> minted = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            String raw = randomCode();
            GiftCard card = new GiftCard();
            card.setCodeHash(hash(PREFIX + raw));
            card.setLast4(raw.substring(raw.length() - 4));
            card.setAmount(amount);
            card.setCreatedAt(now);
            card.setCreatedBy(actor);
            card.setNote(note);
            card.setExpiresAt(expiresAt);
            GiftCard saved = cards.save(card);
            minted.add(new MintedCard(saved.getId(), display(raw), saved.getLast4(), amount, expiresAt));
        }
        return new MintResult(minted, StoreCreditService.money(amount * minted.size()));
    }

    @Transactional
    public RedeemResult redeem(long phno, String code) {
        String normalized = normalize(code);
        GiftCard card = normalized == null ? null : cards.findByCodeHash(hash(normalized)).orElse(null);
        if (card == null) {
            throw new ProductException("That gift card code isn't valid. Check it and try again.");
        }
        Instant now = clock.instant();
        // Say why, so the holder of a real code isn't left guessing; this reveals nothing to someone without the code.
        if (card.isVoided()) {
            throw new ProductException("This gift card has been cancelled.");
        }
        if (card.getRedeemedAt() != null) {
            throw new ProductException("This gift card has already been used.");
        }
        if (card.getExpiresAt() != null && !card.getExpiresAt().isAfter(now)) {
            throw new ProductException("This gift card has expired.");
        }
        if (cards.claim(card.getId(), phno, now) != 1) {
            // Lost a race with someone else redeeming it at the same moment.
            throw new ProductException("This gift card has already been used.");
        }
        storeCreditService.credit(phno, card.getAmount(), StoreCreditTransaction.Type.GIFT_CARD, null,
                "Gift card ending " + card.getLast4());
        return new RedeemResult(card.getAmount(), storeCreditService.balance(phno));
    }

    @Transactional(readOnly = true)
    public GiftCardList list(String status, int limit) {
        if (limit < 1 || limit > MAX_LIST) {
            throw new ProductException("Limit must be between 1 and " + MAX_LIST);
        }
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (wanted != null && !STATUSES.contains(wanted)) {
            throw new ProductException("Status must be one of " + String.join(", ", new java.util.TreeSet<>(STATUSES)));
        }
        Instant now = clock.instant();
        List<GiftCardView> views = cards.findAllByOrderByIdDesc(PageRequest.of(0, MAX_LIST)).stream()
                .map(c -> view(c, now))
                .filter(v -> wanted == null || v.status().equals(wanted))
                .limit(limit)
                .toList();
        List<GiftCard> all = cards.findAll();
        long active = all.stream().filter(c -> statusOf(c, now).equals("ACTIVE")).count();
        double value = all.stream().filter(c -> statusOf(c, now).equals("ACTIVE")).mapToDouble(GiftCard::getAmount).sum();
        return new GiftCardList((int) active, StoreCreditService.money(value), views);
    }

    /** Cancels a card that hasn't been used. A redeemed card's value is already in a wallet - adjust that instead. */
    @Transactional
    public GiftCardView voidCard(long id) {
        GiftCard card = cards.findById(id).orElseThrow(() -> new OrderNotFoundException("Gift card not found"));
        if (card.getRedeemedAt() != null) {
            throw new ProductException("This card was already redeemed; its value is in the customer's store credit.");
        }
        if (!card.isVoided()) {
            card.setVoided(true);
            card.setVoidedAt(clock.instant());
            cards.save(card);
        }
        return view(card, clock.instant());
    }

    private GiftCardView view(GiftCard c, Instant now) {
        return new GiftCardView(c.getId(), c.getLast4(), c.getAmount(), statusOf(c, now), c.getCreatedAt(), c.getCreatedBy(),
                c.getNote(), c.getExpiresAt(), c.getRedeemedBy(), c.getRedeemedAt());
    }

    private static String statusOf(GiftCard c, Instant now) {
        if (c.isVoided()) {
            return "VOIDED";
        }
        if (c.getRedeemedAt() != null) {
            return "REDEEMED";
        }
        return c.getExpiresAt() != null && !c.getExpiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // GC-ABCD-EFGH-JKMN-PQRS
    private static String display(String raw) {
        return PREFIX + "-" + raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12);
    }

    // "gc abcd-efgh..." -> "GCABCDEFGH...", or null if it can't be a code (wrong length or characters).
    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String s = code.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (s.length() != PREFIX.length() + CODE_LENGTH || !s.startsWith(PREFIX)) {
            return null;
        }
        for (int i = PREFIX.length(); i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                return null;
            }
        }
        return s;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
