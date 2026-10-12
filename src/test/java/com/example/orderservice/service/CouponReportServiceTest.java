package com.example.orderservice.service;

import com.example.orderservice.dto.CouponReport;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CouponRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private final CartRepository orders = mock(CartRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponReportService service = new CouponReportService(orders, coupons, Clock.fixed(NOW, ZoneOffset.UTC));

    private Cart order(String code, OrderStatus status, double total, double refunded, double discount) {
        Cart c = new Cart();
        c.setCouponCode(code);
        c.setStatus(status);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        c.setDiscountAmount(discount);
        return c;
    }

    private Coupon coupon(String code, double percent, boolean active, Instant expiry) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountPercent(percent);
        c.setActive(active);
        c.setExpiryDate(expiry);
        c.setRedemptionCount(2);
        return c;
    }

    @Test
    void sumsDiscountAndNetRevenueOverStandingOrdersAndCountsTheLostOnes() {
        when(orders.findAll()).thenReturn(List.of(
                order("SAVE10", OrderStatus.DELIVERED, 450, 50, 50),
                order("save10", OrderStatus.PLACED, 270, 0, 30),
                order("SAVE10", OrderStatus.CANCELLED, 90, 90, 10),
                order("SAVE10", OrderStatus.PENDING_PAYMENT, 90, 0, 10),
                order(null, OrderStatus.PLACED, 100, 0, 0),
                order("SAVE10", null, 90, 0, 10)));
        when(coupons.findAll()).thenReturn(List.of(coupon("SAVE10", 10, true, null)));

        CouponReport.Row row = service.report().rows().get(0);

        assertEquals("SAVE10", row.code());
        assertEquals(3, row.orders());
        assertEquals(2, row.standingOrders());
        assertEquals(1, row.cancelledOrReturned());
        assertEquals(80.0, row.discountGiven());
        assertEquals(670.0, row.netRevenue());
        assertEquals(335.0, row.averageOrderValue());
    }

    @Test
    void aDeletedCouponStillShowsAndAnUnusedOneShowsZero() {
        when(orders.findAll()).thenReturn(List.of(order("GONE", OrderStatus.SHIPPED, 100, 0, 20)));
        when(coupons.findAll()).thenReturn(List.of(coupon("OLD", 5, true, NOW.minusSeconds(60))));

        CouponReport report = service.report();

        CouponReport.Row gone = report.rows().get(0);
        assertEquals("GONE", gone.code());
        assertFalse(gone.known());
        assertNull(gone.discountPercent());
        CouponReport.Row old = report.rows().get(1);
        assertTrue(old.expired());
        assertEquals(0, old.orders());
        assertNull(old.averageOrderValue());
        assertEquals(20.0, report.totalDiscountGiven());
    }
}
