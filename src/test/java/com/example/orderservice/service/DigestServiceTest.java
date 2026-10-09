package com.example.orderservice.service;

import com.example.orderservice.dto.DigestResult;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.ModerationReview;
import com.example.orderservice.dto.RevenueTimeseries;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestServiceTest {

    // 10:00 UTC on the 7th is 15:30 in Kolkata, so "yesterday" is the 6th.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 6);

    @Mock
    private OrderService orderService;
    @Mock
    private CartRepository orders;
    @Mock
    private MailService mailService;

    private DigestService service(String to) {
        return new DigestService(orderService, orders, mailService, CLOCK, to, "Asia/Kolkata");
    }

    private RevenueTimeseries series(long orders, double revenue) {
        return new RevenueTimeseries(YESTERDAY, YESTERDAY, "day", "Asia/Kolkata",
                List.of(new RevenueTimeseries.Point(YESTERDAY, orders, revenue)), revenue, orders, 0);
    }

    private List<Cart> carts(int count) {
        List<Cart> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Cart());
        }
        return list;
    }

    @BeforeEach
    void healthyDefaults() {
        when(orderService.getRevenueTimeseries(YESTERDAY, YESTERDAY, "day", "Asia/Kolkata")).thenReturn(series(3, 1250.5));
        when(orderService.getRevenueTimeseries(YESTERDAY.minusDays(6), YESTERDAY, "day", "Asia/Kolkata")).thenReturn(series(11, 9000));
        when(orders.findByStatus(OrderStatus.PLACED)).thenReturn(carts(4));
        when(orders.findByStatus(OrderStatus.SHIPPED)).thenReturn(carts(2));
        when(orders.findByStatus(OrderStatus.PENDING_PAYMENT)).thenReturn(carts(1));
        when(orderService.getFlaggedReviews(0, 200)).thenReturn(List.of(mockReview()));
        when(orderService.getLowStockReport()).thenReturn(List.of(
                new LowStockItem(1, "Soap", 0, 5, "OUT", 3), new LowStockItem(2, "Tea", 4, 10, "LOW", 0)));
    }

    private ModerationReview mockReview() {
        return new ModerationReview(1, 1, "A", 9000000001L, 1, "bad", null, true, "spam", false);
    }

    @Test
    void sendEmailsTheDigestWithYesterdaysFiguresAndEverythingWaiting() {
        when(mailService.send(eq("owner@example.com"), anyString(), anyString())).thenReturn(true);

        DigestResult result = service("owner@example.com").send();

        assertTrue(result.sent());
        assertEquals("Charan Mart daily digest - 2026-10-06", result.subject());
        String body = result.body();
        assertTrue(body.contains("Yesterday:   3 order(s), Rs. 1250.50"));
        assertTrue(body.contains("Last 7 days: 11 order(s), Rs. 9000.00"));
        assertTrue(body.contains("Orders to ship:            4"));
        assertTrue(body.contains("Shipped, to mark delivered: 2"));
        assertTrue(body.contains("Unpaid UPI orders:         1"));
        assertTrue(body.contains("Flagged reviews:           1"));
        assertTrue(body.contains("OUT  Soap - 0 left (threshold 5), 3 waiting"));
        assertTrue(body.contains("LOW  Tea - 4 left (threshold 10)"));
        verify(mailService).send(eq("owner@example.com"), eq("Charan Mart daily digest - 2026-10-06"), anyString());
    }

    @Test
    void withoutARecipientNothingIsSentAndTheReasonSaysSo() {
        DigestResult result = service("  ").send();

        assertFalse(result.sent());
        assertNull(result.to());
        assertTrue(result.reason().contains("ADMIN_EMAIL"));
        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void previewBuildsTheSameTextWithoutSending() {
        DigestResult result = service("owner@example.com").preview();

        assertFalse(result.sent());
        assertTrue(result.body().contains("Orders to ship:            4"));
        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aMailServerThatRefusesTheDigestIsReportedNotThrown() {
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        DigestResult result = service("owner@example.com").send();

        assertFalse(result.sent());
        assertNotNull(result.reason());
    }

    // ProductService being down must cost the owner one section, not the whole digest.
    @Test
    void oneFailingSectionIsReplacedByANoticeAndTheRestStillArrives() {
        when(orderService.getLowStockReport()).thenThrow(new IllegalStateException("ProductService down"));
        when(orderService.getFlaggedReviews(0, 200)).thenThrow(new IllegalStateException("ProductService down"));
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        DigestResult result = service("owner@example.com").send();

        assertTrue(result.sent());
        assertTrue(result.body().contains("Yesterday:   3 order(s)"));
        assertTrue(result.body().split("\\(not available right now\\)", -1).length - 1 == 2);
    }

    @Test
    void aLongLowStockListIsCappedWithAMoreLine() {
        List<LowStockItem> many = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            many.add(new LowStockItem(i, "Item " + i, 1, 5, "LOW", 0));
        }
        when(orderService.getLowStockReport()).thenReturn(many);

        String body = service("owner@example.com").preview().body();

        assertTrue(body.contains("Item 15 - 1 left"));
        assertFalse(body.contains("Item 16 - 1 left"));
        assertTrue(body.contains("... and 5 more"));
    }

    private com.example.orderservice.dto.RecentStockChange change(String name, String type, int delta, int after, String reason, String actor) {
        return new com.example.orderservice.dto.RecentStockChange(1, name, type, delta, after, reason, null, actor, Instant.parse("2026-10-07T03:00:00Z"));
    }

    @Test
    void handMadeStockChangesAreListedAndBigOnesFlagged() {
        when(orderService.getRecentStockChanges(24)).thenReturn(List.of(
                change("Rice", "CORRECTION", -3, 7, "damaged", "asha"),
                change("Soap", "RESTOCK", 50, 80, "supplier delivery", "ravi")));

        String body = service("owner@example.com").preview().body();

        assertTrue(body.contains("Stock changes made by hand (last 24 hours)"));
        assertTrue(body.contains("     Correction Rice -3 (now 7) - damaged [asha]"));
        assertTrue(body.contains("BIG  Restock    Soap +50 (now 80) - supplier delivery [ravi]"));
    }

    @Test
    void noHandMadeStockChangesSaysSo() {
        when(orderService.getRecentStockChanges(24)).thenReturn(List.of());

        assertTrue(service("owner@example.com").preview().body().contains("No corrections or restock receipts."));
    }

    @Test
    void aLongStockChangeListIsCappedAndAFailureCostsOnlyThatSection() {
        List<com.example.orderservice.dto.RecentStockChange> many = new ArrayList<>();
        for (int i = 1; i <= 18; i++) {
            many.add(change("Item " + i, "CORRECTION", 1, 5, null, null));
        }
        when(orderService.getRecentStockChanges(24)).thenReturn(many);

        String body = service("owner@example.com").preview().body();
        assertTrue(body.contains("Item 15 +1 (now 5)"));
        assertFalse(body.contains("Item 16 +1"));
        assertTrue(body.contains("... and 3 more"));

        when(orderService.getRecentStockChanges(24)).thenThrow(new IllegalStateException("ProductService down"));
        String degraded = service("owner@example.com").preview().body();
        assertTrue(degraded.contains("Yesterday:   3 order(s)"));
        assertTrue(degraded.contains("(not available right now)"));
    }
}
