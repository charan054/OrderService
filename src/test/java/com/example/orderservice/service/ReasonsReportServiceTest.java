package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ReasonsReport;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReasonsReportServiceTest {
    @Mock
    private CartRepository carts;
    @Mock
    private TrackingEventRepository tracking;
    @Mock
    private ProductClient productClient;

    private ReasonsReportService service;

    @BeforeEach
    void setUp() {
        service = new ReasonsReportService(carts, tracking, productClient,
                Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
    }

    private OrderItem line(int productId, int ordered, int cancelled, int returned, String returnReason) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(ordered);
        i.setCancelledQuantity(cancelled);
        i.setReturnedQuantity(returned);
        i.setReturnReason(returnReason);
        return i;
    }

    private Cart order(long id, OrderStatus status, OrderItem... items) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setStatus(status);
        c.setOrderItems(List.of(items));
        return c;
    }

    private TrackingEvent placed(long orderId, String when) {
        TrackingEvent e = new TrackingEvent();
        e.setOrderId(orderId);
        e.setTimestamp(Instant.parse(when));
        return e;
    }

    private Product product(int id, String name) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName(name);
        return p;
    }

    @Test
    void countsReasonsWeeksAndProducts() {
        Cart cancelled = order(1, OrderStatus.CANCELLED, line(5, 2, 2, 0, null));
        cancelled.setCancelReason("CHANGED_MIND");
        Cart returned = order(2, OrderStatus.DELIVERED, line(5, 3, 0, 1, "  Too  SMALL "), line(6, 1, 0, 0, null));
        Cart returnedWhole = order(3, OrderStatus.RETURNED, line(6, 1, 0, 1, null));
        returnedWhole.setReturnReason("too small");
        Cart fine = order(4, OrderStatus.DELIVERED, line(5, 1, 0, 0, null));
        when(productClient.getProductById(5)).thenReturn(product(5, "Mug"));
        when(productClient.getProductById(6)).thenReturn(product(6, "Plate"));

        ReasonsReport r = service.build(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14),
                List.of(cancelled, returned, returnedWhole, fine),
                java.util.Map.of(1L, Instant.parse("2026-10-05T10:00:00Z"), 2L, Instant.parse("2026-10-07T10:00:00Z"),
                        3L, Instant.parse("2026-10-13T10:00:00Z"), 4L, Instant.parse("2026-10-14T10:00:00Z")));

        assertEquals(4, r.ordersPlaced());
        assertEquals(1, r.ordersWithCancellations());
        assertEquals(2, r.ordersWithReturns());
        assertEquals(2, r.unitsCancelled());
        assertEquals(2, r.unitsReturned());
        assertEquals(List.of(new ReasonsReport.Count("changed mind", 2)), r.cancelReasons());
        assertEquals(List.of(new ReasonsReport.Count("too small", 2)), r.returnReasons(), "case and spacing are ignored");
        // Mon 5 Oct: orders 1 and 2; Mon 12 Oct: orders 3 and 4
        assertEquals(2, r.weeks().size());
        assertEquals(LocalDate.of(2026, 10, 5), r.weeks().get(0).weekStart());
        assertEquals(2, r.weeks().get(0).ordersPlaced());
        assertEquals(2, r.weeks().get(0).unitsCancelled());
        assertEquals(1, r.weeks().get(0).unitsReturned());
        assertEquals(LocalDate.of(2026, 10, 12), r.weeks().get(1).weekStart());
        // Mug: 6 ordered, 2 cancelled, 1 returned -> 50%; Plate: 2 ordered, 1 returned -> 50%
        assertEquals("Mug", r.products().get(0).productName());
        assertEquals(50.0, r.products().get(0).sentBackPercent());
        assertEquals(2, r.products().size());
    }

    @Test
    void anOldWholeOrderCancelWithoutZeroedLinesStillCounts() {
        Cart old = order(1, OrderStatus.CANCELLED, line(5, 4, 0, 0, null));
        when(productClient.getProductById(5)).thenReturn(product(5, "Mug"));

        ReasonsReport r = service.build(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14), List.of(old),
                java.util.Map.of(1L, Instant.parse("2026-10-05T10:00:00Z")));

        assertEquals(4, r.unitsCancelled());
        assertEquals("not given", r.cancelReasons().get(0).reason());
    }

    @Test
    void reportUsesOnlyOrdersPlacedInTheRange() {
        Cart inside = order(1, OrderStatus.CANCELLED, line(5, 1, 1, 0, null));
        Cart outside = order(2, OrderStatus.CANCELLED, line(5, 1, 1, 0, null));
        when(carts.findAll()).thenReturn(List.of(inside, outside));
        when(tracking.findAll()).thenReturn(List.of(placed(1, "2026-10-05T10:00:00Z"), placed(2, "2026-08-01T10:00:00Z")));
        when(productClient.getProductById(5)).thenReturn(product(5, "Mug"));

        ReasonsReport r = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14));

        assertEquals(1, r.ordersPlaced());
        assertEquals(1, r.unitsCancelled());
    }

    @Test
    void rangeDefaultsToTheLastNinetyDaysAndIsValidated() {
        when(carts.findAll()).thenReturn(List.of());
        when(tracking.findAll()).thenReturn(List.of());

        ReasonsReport r = service.report(null, null);

        assertEquals(LocalDate.of(2026, 10, 14), r.to());
        assertEquals(LocalDate.of(2026, 7, 17), r.from());
        assertThrows(ProductException.class, () -> service.report(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)));
        assertThrows(ProductException.class, () -> service.report(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void csvQuotesTextAndDefusesFormulas() {
        ReasonsReport r = new ReasonsReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14), 3, 0, 1, 0, 1,
                List.of(), List.of(new ReasonsReport.Count("=cmd, \"x\"", 1)), List.of(), List.of());

        String csv = service.csv(r);

        assertTrue(csv.startsWith("section,key,"));
        assertTrue(csv.contains("\"'=cmd, \"\"x\"\"\""), csv);
    }
}
