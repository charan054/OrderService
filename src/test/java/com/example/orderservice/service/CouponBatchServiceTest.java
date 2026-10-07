package com.example.orderservice.service;

import com.example.orderservice.dto.BulkCouponRequest;
import com.example.orderservice.dto.BulkCouponResult;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private CouponRepository coupons;

    private CouponBatchService service;

    @BeforeEach
    void setUp() {
        service = new CouponBatchService(coupons, Clock.fixed(NOW, ZoneOffset.UTC), new Random(42));
        lenient().when(coupons.existsById(anyString())).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private List<Coupon> saved() {
        ArgumentCaptor<Iterable<Coupon>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(coupons).saveAll(captor.capture());
        List<Coupon> list = new java.util.ArrayList<>();
        captor.getValue().forEach(list::add);
        return list;
    }

    @Test
    void mintsDistinctSingleUseUnlistedCodesWithThePrefix() {
        BulkCouponResult result = service.generate(new BulkCouponRequest("gift", 50, 20, null));

        assertEquals("GIFT", result.prefix());
        assertEquals(50, result.codes().size());
        assertEquals(50, new HashSet<>(result.codes()).size());
        assertTrue(result.codes().stream().allMatch(c -> c.matches("GIFT-[A-HJKMNP-Z2-9]{8}")));
        List<Coupon> saved = saved();
        assertEquals(50, saved.size());
        for (Coupon c : saved) {
            assertEquals(20.0, c.getDiscountPercent());
            assertTrue(c.isActive());
            assertTrue(c.isUnlisted());
            assertEquals(1, c.getMaxRedemptions());
            assertEquals(1, c.getPerCustomerLimit());
            assertNull(c.getExpiryDate());
        }
        assertEquals(result.codes(), saved.stream().map(Coupon::getCode).toList());
    }

    @Test
    void aBlankPrefixDefaultsAndTheExpiryIsApplied() {
        Instant expiry = NOW.plusSeconds(86400);

        BulkCouponResult result = service.generate(new BulkCouponRequest("  ", 2, 10, expiry));

        assertEquals("GIFT", result.prefix());
        assertEquals(expiry, result.expiryDate());
        assertTrue(saved().stream().allMatch(c -> expiry.equals(c.getExpiryDate())));
    }

    @Test
    void aCodeThatAlreadyExistsIsSkipped() {
        // Whatever the first draw is, claim it exists; the batch must still come out with the full count.
        when(coupons.existsById(anyString())).thenReturn(true, false);

        BulkCouponResult result = service.generate(new BulkCouponRequest("WIN", 3, 10, null));

        assertEquals(3, result.codes().size());
        assertEquals(3, new HashSet<>(result.codes()).size());
    }

    @Test
    void givesUpRatherThanLoopingWhenEveryCodeIsTaken() {
        when(coupons.existsById(anyString())).thenReturn(true);

        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("WIN", 3, 10, null)));
        verify(coupons, never()).saveAll(anyIterable());
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnythingIsSaved() {
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("X", 5, 10, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("TOOLONGPREFIX1", 5, 10, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("A-B", 5, 10, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("OK", 0, 10, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("OK", 501, 10, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("OK", 5, 0, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("OK", 5, 101, null)));
        assertThrows(ProductException.class, () -> service.generate(new BulkCouponRequest("OK", 5, 10, NOW.minusSeconds(1))));
        verify(coupons, never()).saveAll(any());
    }
}
