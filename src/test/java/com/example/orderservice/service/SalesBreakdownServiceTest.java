package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.SalesBreakdown;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesBreakdownServiceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Mock
    private CartRepository orders;
    @Mock
    private TrackingEventRepository tracking;
    @Mock
    private ProductClient productClient;

    private SalesBreakdownService service;
    private final List<Cart> carts = new ArrayList<>();
    private final List<TrackingEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SalesBreakdownService(orders, tracking, productClient);
        lenient().when(orders.findAll()).thenReturn(carts);
        lenient().when(tracking.findAll()).thenReturn(events);
        lenient().when(productClient.findAll()).thenReturn(List.of(
                product(1, "Soap", "care"), product(2, "Shampoo", "care"), product(3, "Rice", "grocery")));
    }

    private Product product(int id, String name, String category) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName(name);
        p.setProductCategory(category);
        return p;
    }

    private OrderItem item(int productId, int quantity, Double unitPrice) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(quantity);
        i.setUnitPrice(unitPrice);
        return i;
    }

    // Adds a placed order of the given lines; total is what was charged, placedOn is its first tracking day.
    private Cart order(long id, OrderStatus status, double total, String placedAt, OrderItem... items) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setStatus(status);
        c.setTotalPrice(total);
        c.setOrderItems(new ArrayList<>(List.of(items)));
        carts.add(c);
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(id);
        e.setTimestamp(Instant.parse(placedAt));
        events.add(e);
        return c;
    }

    private SalesBreakdown run(String groupBy, String sort, String dir) {
        return service.breakdown(FROM, TO, "UTC", groupBy, sort, dir, null);
    }

    @Test
    void sharesAnOrdersNetAmountOverItsLinesByWhatTheyCost() {
        // 100 + 300 of goods, a coupon took it down to 360: the lines get 90 and 270.
        order(1, OrderStatus.DELIVERED, 360, "2026-10-05T10:00:00Z", item(1, 1, 100.0), item(3, 1, 300.0));

        SalesBreakdown result = run("product", null, null);

        assertEquals(360.0, result.totalRevenue());
        assertEquals(2, result.totalUnits());
        assertEquals(1, result.totalOrders());
        assertEquals("Rice", result.rows().get(0).label());
        assertEquals(270.0, result.rows().get(0).revenue());
        assertEquals(75.0, result.rows().get(0).sharePercent());
        assertEquals(90.0, result.rows().get(1).revenue());
        assertEquals("care", result.rows().get(1).category());
    }

    @Test
    void groupingByCategoryAddsProductsTogetherAndCountsAnOrderOncePerCategory() {
        order(1, OrderStatus.DELIVERED, 300, "2026-10-05T10:00:00Z", item(1, 1, 100.0), item(2, 1, 200.0));
        order(2, OrderStatus.PLACED, 50, "2026-10-06T10:00:00Z", item(1, 1, 50.0));

        SalesBreakdown result = run("category", null, null);

        assertEquals(1, result.rows().size());
        SalesBreakdown.Row care = result.rows().get(0);
        assertEquals("care", care.label());
        assertEquals(3, care.units());
        assertEquals(2, care.orders());
        assertEquals(350.0, care.revenue());
    }

    @Test
    void cancelledReturnedUnpaidAndOutOfRangeOrdersAreLeftOut() {
        order(1, OrderStatus.CANCELLED, 100, "2026-10-05T10:00:00Z", item(1, 1, 100.0));
        order(2, OrderStatus.RETURNED, 100, "2026-10-05T10:00:00Z", item(1, 1, 100.0));
        order(3, OrderStatus.PENDING_PAYMENT, 100, "2026-10-05T10:00:00Z", item(1, 1, 100.0));
        order(4, OrderStatus.DELIVERED, 100, "2026-09-30T23:59:59Z", item(1, 1, 100.0));
        order(5, OrderStatus.DELIVERED, 100, "2026-11-01T00:00:00Z", item(1, 1, 100.0));
        order(6, OrderStatus.DELIVERED, 40, "2026-10-31T23:59:59Z", item(2, 1, 40.0));

        SalesBreakdown result = run("product", null, null);

        assertEquals(1, result.rows().size());
        assertEquals("Shampoo", result.rows().get(0).label());
        assertEquals(40.0, result.totalRevenue());
    }

    @Test
    void anOrderWithNoTrackingHistoryCannotBePlacedOnTheAxisSoItIsLeftOut() {
        Cart c = new Cart();
        c.setOrderId(9L);
        c.setStatus(OrderStatus.DELIVERED);
        c.setTotalPrice(10);
        c.setOrderItems(new ArrayList<>(List.of(item(1, 1, 10.0))));
        carts.add(c);

        assertTrue(run("product", null, null).rows().isEmpty());
    }

    @Test
    void theDayIsTheCallersDayNotUtc() {
        // 20:00 UTC on 31 Oct is already 1 Nov in Kolkata, so it falls outside October there.
        order(1, OrderStatus.DELIVERED, 100, "2026-10-31T20:00:00Z", item(1, 1, 100.0));

        assertEquals(1, run("product", null, null).rows().size());
        assertTrue(service.breakdown(FROM, TO, "Asia/Kolkata", "product", null, null, null).rows().isEmpty());
    }

    @Test
    void unitsAndRevenueIgnoreWhatWasCancelledOrReturnedPerItemAndRefundsLowerRevenue() {
        OrderItem partlyCancelled = item(1, 3, 100.0);
        partlyCancelled.setCancelledQuantity(1);
        Cart c = order(1, OrderStatus.DELIVERED, 300, "2026-10-05T10:00:00Z", partlyCancelled, item(3, 1, 100.0));
        c.setRefundedAmount(100);

        SalesBreakdown result = run("product", "units", "desc");

        // Net 200 over 2 soap (200 of weight) + 1 rice (100): soap 133.33, rice 66.67; soap shows 2 units, not 3.
        assertEquals(2, result.rows().get(0).units());
        assertEquals(133.33, result.rows().get(0).revenue());
        assertEquals(66.67, result.rows().get(1).revenue());
        assertEquals(200.0, result.totalRevenue(), 0.011);
    }

    @Test
    void ordersFromBeforeUnitPricesWereRecordedAreSharedByUnits() {
        order(1, OrderStatus.DELIVERED, 90, "2026-10-05T10:00:00Z", item(1, 2, null), item(3, 1, null));

        SalesBreakdown result = run("product", null, null);

        assertEquals(60.0, result.rows().get(0).revenue());
        assertEquals(30.0, result.rows().get(1).revenue());
    }

    @Test
    void sortsByTheChosenColumnAndDirection() {
        order(1, OrderStatus.DELIVERED, 100, "2026-10-05T10:00:00Z", item(1, 5, 20.0));
        order(2, OrderStatus.DELIVERED, 300, "2026-10-05T10:00:00Z", item(3, 1, 300.0));
        order(3, OrderStatus.DELIVERED, 50, "2026-10-05T10:00:00Z", item(2, 2, 25.0));

        assertEquals(List.of("Rice", "Soap", "Shampoo"), run("product", null, null).rows().stream().map(SalesBreakdown.Row::label).toList());
        assertEquals(List.of("Soap", "Shampoo", "Rice"), run("product", "units", null).rows().stream().map(SalesBreakdown.Row::label).toList());
        assertEquals(List.of("Rice", "Shampoo", "Soap"), run("product", "label", null).rows().stream().map(SalesBreakdown.Row::label).toList());
        assertEquals(List.of("Shampoo", "Soap", "Rice"), run("product", "revenue", "asc").rows().stream().map(SalesBreakdown.Row::label).toList());
    }

    @Test
    void theLimitTrimsTheRowsButNotTheTotals() {
        order(1, OrderStatus.DELIVERED, 100, "2026-10-05T10:00:00Z", item(1, 1, 100.0));
        order(2, OrderStatus.DELIVERED, 200, "2026-10-05T10:00:00Z", item(3, 1, 200.0));
        order(3, OrderStatus.DELIVERED, 50, "2026-10-05T10:00:00Z", item(2, 1, 50.0));

        SalesBreakdown result = service.breakdown(FROM, TO, "UTC", "product", null, null, 2);

        assertEquals(2, result.rows().size());
        assertTrue(result.truncated());
        assertEquals(350.0, result.totalRevenue());
        assertEquals(3, result.totalUnits());
        assertFalse(service.breakdown(FROM, TO, "UTC", "product", null, null, 3).truncated());
    }

    @Test
    void whenTheCatalogIsDownRowsFallBackToIdsAndUnknownCategory() {
        when(productClient.findAll()).thenThrow(new IllegalStateException("ProductService down"));
        order(1, OrderStatus.DELIVERED, 100, "2026-10-05T10:00:00Z", item(7, 1, 100.0));

        SalesBreakdown byProduct = run("product", null, null);
        SalesBreakdown byCategory = run("category", null, null);

        assertEquals("Product #7", byProduct.rows().get(0).label());
        assertEquals("(unknown)", byProduct.rows().get(0).category());
        assertEquals("(unknown)", byCategory.rows().get(0).label());
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(ProductException.class, () -> service.breakdown(FROM, TO, "Mars/Base", "product", null, null, null));
        assertThrows(ProductException.class, () -> run("brand", null, null));
        assertThrows(ProductException.class, () -> run("product", "price; drop table", null));
        assertThrows(ProductException.class, () -> run("product", null, "sideways"));
        assertThrows(ProductException.class, () -> service.breakdown(TO, FROM, "UTC", "product", null, null, null));
        assertThrows(ProductException.class, () -> service.breakdown(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1), "UTC", "product", null, null, null));
    }

    @Test
    void csvHasAHeaderOneRowPerGroupAndNeutralisesFormulaNames() {
        when(productClient.findAll()).thenReturn(List.of(product(1, "=HYPERLINK(\"x\")", "care, home")));
        order(1, OrderStatus.DELIVERED, 100, "2026-10-05T10:00:00Z", item(1, 2, 50.0));

        String csv = service.exportCsv(FROM, TO, "UTC", "product", null, null);
        String[] lines = csv.split("\r\n");

        assertEquals("productId,product,category,units,orders,revenue,sharePercent", lines[0]);
        assertEquals("1,\"'=HYPERLINK(\"\"x\"\")\",\"care, home\",2,1,100.00,100.0", lines[1]);
        assertTrue(service.exportCsv(FROM, TO, "UTC", "category", null, null).startsWith("category,units,orders,revenue,sharePercent"));
    }
}
