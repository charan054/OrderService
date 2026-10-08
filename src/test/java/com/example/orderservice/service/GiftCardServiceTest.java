package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.GiftCardDtos.GiftCardList;
import com.example.orderservice.dto.GiftCardDtos.MintRequest;
import com.example.orderservice.dto.GiftCardDtos.MintResult;
import com.example.orderservice.dto.GiftCardDtos.MintedCard;
import com.example.orderservice.dto.GiftCardDtos.RedeemResult;
import com.example.orderservice.entity.GiftCard;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.GiftCardRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Gift cards against the real (in-memory) database; the clock is mocked so expiry needs no sleeping. */
@SpringBootTest
@ActiveProfiles("test")
class GiftCardServiceTest {
    private static final long ASHA = 9876500201L;
    private static final long RAVI = 9876500202L;

    @Autowired
    private GiftCardService service;
    @Autowired
    private GiftCardRepository cards;
    @Autowired
    private StoreCreditService wallet;
    @Autowired
    private StoreCreditAccountRepository accounts;
    @Autowired
    private StoreCreditTransactionRepository transactions;

    @MockitoBean
    private Clock clock;
    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private Instant now;

    @BeforeEach
    void reset() {
        cards.deleteAll();
        transactions.deleteAll();
        accounts.deleteAll();
        now = Instant.parse("2026-10-08T10:00:00Z");
        when(clock.instant()).thenAnswer(invocation -> now);
    }

    private MintedCard mintOne(double amount) {
        return service.mint(new MintRequest(1, amount, null, null), "owner1").cards().get(0);
    }

    private void assertRejected(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOf(ProductException.class).hasMessageContaining(message);
    }

    @Test
    void mintingMakesWellFormedUniqueCodesAndKeepsOnlyHashes() {
        MintResult result = service.mint(new MintRequest(25, 500, "  Diwali gifts ", null), "owner1");

        assertThat(result.cards()).hasSize(25);
        assertThat(result.totalValue()).isEqualTo(12500.0);
        assertThat(result.cards().stream().map(MintedCard::code).collect(Collectors.toSet())).hasSize(25);
        assertThat(result.cards()).allSatisfy(c -> {
            assertThat(c.code()).matches("GC-[A-HJKMN-Z2-9]{4}(-[A-HJKMN-Z2-9]{4}){3}");
            assertThat(c.code()).endsWith(c.last4());
        });
        String firstPlain = result.cards().get(0).code().replace("-", "");
        assertThat(cards.findAll()).hasSize(25).allSatisfy(c -> {
            assertThat(c.getCodeHash()).hasSize(64);
            assertThat(c.getCodeHash()).doesNotContain(firstPlain.substring(2, 8));
            assertThat(c.getNote()).isEqualTo("Diwali gifts");
            assertThat(c.getCreatedBy()).isEqualTo("owner1");
            assertThat(c.getAmount()).isEqualTo(500.0);
            assertThat(c.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(365)));
        });
    }

    @Test
    void redeemingPutsTheValueInTheWalletAndLeavesAnEntryInItsHistory() {
        MintedCard card = mintOne(750);
        wallet.credit(ASHA, 50, StoreCreditTransaction.Type.REFUND, null, "earlier refund");

        RedeemResult result = service.redeem(ASHA, card.code());

        assertThat(result.amount()).isEqualTo(750.0);
        assertThat(result.balance()).isEqualTo(800.0);
        assertThat(wallet.balance(ASHA)).isEqualTo(800.0);
        assertThat(wallet.summary(ASHA).transactions().get(0)).satisfies(t -> {
            assertThat(t.getType()).isEqualTo(StoreCreditTransaction.Type.GIFT_CARD);
            assertThat(t.getAmount()).isEqualTo(750.0);
            assertThat(t.getBalanceAfter()).isEqualTo(800.0);
            assertThat(t.getNote()).isEqualTo("Gift card ending " + card.last4());
        });
        GiftCard stored = cards.findById(card.id()).orElseThrow();
        assertThat(stored.getRedeemedBy()).isEqualTo(ASHA);
        assertThat(stored.getRedeemedAt()).isEqualTo(now);
    }

    @Test
    void theCodeIsForgivingAboutCaseSpacesAndDashes() {
        MintedCard a = mintOne(100);
        MintedCard b = mintOne(100);
        MintedCard c = mintOne(100);

        service.redeem(ASHA, a.code().toLowerCase());
        service.redeem(ASHA, "  " + b.code().replace("-", " ") + " ");
        service.redeem(ASHA, c.code().replace("-", ""));

        assertThat(wallet.balance(ASHA)).isEqualTo(300.0);
    }

    @Test
    void aCardWorksOnlyOnce() {
        MintedCard card = mintOne(100);
        service.redeem(ASHA, card.code());

        assertRejected(() -> service.redeem(ASHA, card.code()), "already been used");
        assertRejected(() -> service.redeem(RAVI, card.code()), "already been used");
        assertThat(wallet.balance(ASHA)).isEqualTo(100.0);
        assertThat(wallet.balance(RAVI)).isZero();
    }

    @Test
    void twoPeopleSubmittingTheSameCodeAtOnceCannotBothGetPaid() throws Exception {
        MintedCard card = mintOne(100);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> redeemWhenReleased(go, ASHA, card.code()), () -> redeemWhenReleased(go, RAVI, card.code()));
            List<Future<Boolean>> futures = attempts.stream().map(pool::submit).toList();
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    wins++;
                }
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(wallet.balance(ASHA) + wallet.balance(RAVI)).isEqualTo(100.0);
        assertThat(transactions.findAll()).hasSize(1);
    }

    private boolean redeemWhenReleased(CountDownLatch go, long phno, String code) throws InterruptedException {
        go.await();
        try {
            service.redeem(phno, code);
            return true;
        } catch (ProductException e) {
            return false;
        }
    }

    @Test
    void unknownOrMalformedCodesAreRefusedWithoutSayingWhich() {
        mintOne(100);
        for (String bad : new String[]{null, "", "   ", "GC-AAAA", "XX-ABCD-EFGH-JKMN-PQRS", "GC-0000-0000-0000-0000",
                "GC-ABCD-EFGH-JKMN-PQRS", "GC-ABCD-EFGH-JKMN-PQRS-TUVW", "' OR 1=1 --"}) {
            assertRejected(() -> service.redeem(ASHA, bad), "isn't valid");
        }
        assertThat(wallet.balance(ASHA)).isZero();
    }

    @Test
    void anExpiredCardCannotBeRedeemedAndANeverExpiringOneAlways() {
        MintedCard dated = service.mint(new MintRequest(1, 100, null, 30), "owner1").cards().get(0);
        MintedCard forever = service.mint(new MintRequest(1, 100, null, 0), "owner1").cards().get(0);
        assertThat(forever.expiresAt()).isNull();

        now = now.plus(Duration.ofDays(31));

        assertRejected(() -> service.redeem(ASHA, dated.code()), "expired");
        assertThat(service.redeem(ASHA, forever.code()).amount()).isEqualTo(100.0);
        assertThat(wallet.balance(ASHA)).isEqualTo(100.0);
    }

    @Test
    void aCardIsUsableRightUpToItsExpiryInstant() {
        MintedCard card = service.mint(new MintRequest(1, 100, null, 1), "owner1").cards().get(0);
        now = card.expiresAt().minusSeconds(1);

        assertThat(service.redeem(ASHA, card.code()).amount()).isEqualTo(100.0);
    }

    @Test
    void aVoidedCardStopsWorkingAndARedeemedOneCannotBeVoided() {
        MintedCard unused = mintOne(100);
        MintedCard used = mintOne(100);
        service.redeem(ASHA, used.code());

        assertThat(service.voidCard(unused.id()).status()).isEqualTo("VOIDED");
        assertThat(service.voidCard(unused.id()).status()).isEqualTo("VOIDED"); // harmless twice
        assertRejected(() -> service.redeem(RAVI, unused.code()), "cancelled");
        assertRejected(() -> service.voidCard(used.id()), "already redeemed");
        assertThatThrownBy(() -> service.voidCard(-5)).isInstanceOf(OrderNotFoundException.class);
        assertThat(wallet.balance(RAVI)).isZero();
    }

    @Test
    void mintingRejectsBadCountsAmountsNotesAndExpiries() {
        assertRejected(() -> service.mint(new MintRequest(0, 100, null, null), "o"), "between 1 and 100");
        assertRejected(() -> service.mint(new MintRequest(101, 100, null, null), "o"), "between 1 and 100");
        assertRejected(() -> service.mint(new MintRequest(1, 0.5, null, null), "o"), "between Rs. 1");
        assertRejected(() -> service.mint(new MintRequest(1, 10001, null, null), "o"), "between Rs. 1");
        assertRejected(() -> service.mint(new MintRequest(1, Double.NaN, null, null), "o"), "between Rs. 1");
        assertRejected(() -> service.mint(new MintRequest(1, 100, "x".repeat(101), null), "o"), "at most 100");
        assertRejected(() -> service.mint(new MintRequest(1, 100, null, -1), "o"), "Expiry");
        assertRejected(() -> service.mint(new MintRequest(1, 100, null, 1826), "o"), "Expiry");
        assertThat(cards.count()).isZero();
    }

    @Test
    void theListShowsDerivedStatusesAndWhatIsStillOwed() {
        MintedCard active = mintOne(100);
        MintedCard redeemed = mintOne(200);
        MintedCard voided = mintOne(400);
        MintedCard expiring = service.mint(new MintRequest(1, 800, null, 1), "owner1").cards().get(0);
        service.redeem(ASHA, redeemed.code());
        service.voidCard(voided.id());
        now = now.plus(Duration.ofDays(2));
        MintedCard laterActive = service.mint(new MintRequest(1, 1600, "late", 0), "owner1").cards().get(0);

        GiftCardList all = service.list(null, 100);

        assertThat(all.cards()).extracting("id", "status").containsExactly(
                org.assertj.core.groups.Tuple.tuple(laterActive.id(), "ACTIVE"),
                org.assertj.core.groups.Tuple.tuple(expiring.id(), "EXPIRED"),
                org.assertj.core.groups.Tuple.tuple(voided.id(), "VOIDED"),
                org.assertj.core.groups.Tuple.tuple(redeemed.id(), "REDEEMED"),
                org.assertj.core.groups.Tuple.tuple(active.id(), "ACTIVE"));
        // The first card's 365 days haven't passed, so it and the never-expiring one are owed.
        assertThat(all.outstandingCards()).isEqualTo(2);
        assertThat(all.outstandingValue()).isEqualTo(1700.0);
        assertThat(service.list("active", 100).cards()).hasSize(2);
        assertThat(service.list("REDEEMED", 100).cards()).singleElement().satisfies(v -> assertThat(v.redeemedBy()).isEqualTo(ASHA));
        assertThat(service.list(null, 2).cards()).hasSize(2);
        assertThat(all.cards().toString()).doesNotContain("codeHash");
    }

    @Test
    void theListChecksItsArguments() {
        assertRejected(() -> service.list(null, 0), "Limit");
        assertRejected(() -> service.list(null, 501), "Limit");
        assertRejected(() -> service.list("LOST", 10), "Status must be one of");
    }

    @Test
    void normalizeAcceptsOnlyTheRealAlphabet() {
        assertThat(GiftCardService.normalize("gc-abcd-efgh-jkmn-pqrs")).isEqualTo("GCABCDEFGHJKMNPQRS");
        assertThat(GiftCardService.normalize("GC-ABCD-EFGH-JKMN-PQRI")).isNull(); // I is not in the alphabet
        assertThat(GiftCardService.normalize("GC-ABCD-EFGH-JKMN-PQR1")).isNull();
        assertThat(Set.of(GiftCardService.normalize("GC ABCD EFGH JKMN PQRS"))).containsExactly("GCABCDEFGHJKMNPQRS");
    }
}
