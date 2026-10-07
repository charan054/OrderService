package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerExportServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final String HEADER = "customerPhno,customerName,email,marketingEmails,segment,dormant,orders,"
            + "netSpend,firstOrderAt,lastOrderAt,daysSinceLastOrder";

    @Mock
    private CartRepository orders;
    @Mock
    private TrackingEventRepository trackingEvents;
    @Mock
    private CustomerAccountRepository accounts;

    private CustomerExportService service;

    @BeforeEach
    void setUp() {
        service = new CustomerExportService(orders, trackingEvents, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(accounts.findAll()).thenReturn(List.of());
        lenient().when(trackingEvents.findAll()).thenReturn(List.of());
        lenient().when(orders.findAll()).thenReturn(List.of());
    }

    private Cart order(long id, long phno, String name, OrderStatus status, double total, double refunded) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(phno);
        c.setCustomerName(name);
        c.setStatus(status);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        return c;
    }

    private TrackingEvent placed(long orderId, int daysAgo) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(OrderStatus.PLACED);
        e.setTimestamp(NOW.minus(daysAgo, ChronoUnit.DAYS));
        return e;
    }

    private CustomerAccount account(long phno, String email, boolean optOut) {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(phno);
        a.setEmail(email);
        a.setMarketingOptOut(optOut);
        return a;
    }

    // Ann: repeat, active (orders 100 and 3 days ago). Bob: new and dormant (200 days ago). Cy: only a cancelled order.
    private void sampleData() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, 9000000001L, "Ann", OrderStatus.DELIVERED, 500, 100),
                order(2, 9000000001L, "Ann B", OrderStatus.PLACED, 300, 0),
                order(3, 9000000002L, "Bob", OrderStatus.SHIPPED, 200, 0),
                order(4, 9000000003L, "Cy", OrderStatus.CANCELLED, 999, 999)));
        when(trackingEvents.findAll()).thenReturn(List.of(placed(1, 100), placed(1, 90), placed(2, 3), placed(3, 200), placed(4, 1)));
        when(accounts.findAll()).thenReturn(List.of(account(9000000001L, "ann@example.com", false),
                account(9000000002L, "bob@example.com", true)));
    }

    private List<String> lines(String csv) {
        return List.of(csv.split("\r\n"));
    }

    @Test
    void oneRowPerCustomerWithSegmentSpendDatesAndMarketingConsent() {
        sampleData();

        List<String> lines = lines(service.exportCsv("all", 90));

        assertEquals(HEADER, lines.get(0));
        assertEquals(3, lines.size()); // Cy has no order that stands
        assertEquals("9000000001,Ann B,ann@example.com,true,REPEAT,false,2,700.00,2026-06-29T10:00:00Z,2026-10-04T10:00:00Z,3", lines.get(1));
        assertEquals("9000000002,Bob,bob@example.com,false,NEW,true,1,200.00,2026-03-21T10:00:00Z,2026-03-21T10:00:00Z,200", lines.get(2));
    }

    @Test
    void segmentFiltersPickTheRightCustomers() {
        sampleData();

        assertEquals(List.of(HEADER, lines(service.exportCsv("all", 90)).get(1)), lines(service.exportCsv("repeat", 90)));
        assertTrue(lines(service.exportCsv("new", 90)).get(1).startsWith("9000000002,"));
        assertEquals(2, lines(service.exportCsv("new", 90)).size());
        assertTrue(lines(service.exportCsv("dormant", 90)).get(1).startsWith("9000000002,"));
        assertEquals(2, lines(service.exportCsv("active", 90)).size());
        assertTrue(lines(service.exportCsv("active", 90)).get(1).startsWith("9000000001,"));
    }

    @Test
    void theDormantWindowIsConfigurable() {
        sampleData();

        // Bob last ordered 200 days ago, Ann 3 days ago.
        assertEquals(1, lines(service.exportCsv("dormant", 250)).size());   // header only: nobody is 250 days quiet
        assertEquals(3, lines(service.exportCsv("dormant", 2)).size());     // both are quieter than 2 days
    }

    @Test
    void aCustomerWithOnlyUndatedOrdersIsNeverDormantAndHasBlankDates() {
        when(orders.findAll()).thenReturn(List.of(order(9, 9000000009L, "Old", OrderStatus.DELIVERED, 80, 0)));

        List<String> lines = lines(service.exportCsv("all", 90));

        assertEquals("9000000009,Old,,,NEW,false,1,80.00,,,", lines.get(1));
        assertEquals(1, lines(service.exportCsv("dormant", 90)).size());
    }

    @Test
    void ordersWithoutAStatusAreIgnoredInsteadOfFailing() {
        when(orders.findAll()).thenReturn(List.of(order(1, 9000000001L, "Ann", null, 10, 0)));

        assertEquals(List.of(HEADER), lines(service.exportCsv("all", 90)));
    }

    @Test
    void spreadsheetFormulasInANameAreDefused() {
        when(orders.findAll()).thenReturn(List.of(order(1, 9000000001L, "=HYPERLINK(\"x\")", OrderStatus.PLACED, 10, 0)));

        assertTrue(service.exportCsv("all", 90).contains("'=HYPERLINK"));
    }

    @Test
    void anUnknownSegmentOrWindowIsRejected() {
        assertThrows(ProductException.class, () -> service.exportCsv("vip", 90));
        assertThrows(ProductException.class, () -> service.exportCsv("all", 0));
        assertThrows(ProductException.class, () -> service.exportCsv("all", 4000));
    }
}
