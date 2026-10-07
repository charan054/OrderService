package com.example.orderservice.service;

import com.example.orderservice.dto.FeedbackSummary;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderFeedback;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderFeedbackServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private OrderFeedbackRepository feedback;
    @Mock
    private CartRepository orders;

    private OrderFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new OrderFeedbackService(feedback, orders, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Cart order(long id, long phno, OrderStatus status) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(phno);
        c.setStatus(status);
        return c;
    }

    private OrderFeedback fb(int rating, Integer delivery) {
        OrderFeedback f = new OrderFeedback();
        f.setRating(rating);
        f.setDeliveryRating(delivery);
        return f;
    }

    @Test
    void submitSavesFeedbackForADeliveredOrder() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO, OrderStatus.DELIVERED)));
        when(feedback.existsByOrderId(7L)).thenReturn(false);
        when(feedback.save(any(OrderFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderFeedback f = service.submit(PHNO, 7, 4, 5, "  Quick delivery  ");

        assertEquals(4, f.getRating());
        assertEquals(5, f.getDeliveryRating());
        assertEquals("Quick delivery", f.getComment());
        assertEquals(PHNO, f.getCustomerPhno());
        assertEquals(NOW, f.getCreatedAt());
    }

    @Test
    void blankCommentAndMissingDeliveryRatingAreStoredAsNull() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO, OrderStatus.DELIVERED)));
        when(feedback.save(any(OrderFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderFeedback f = service.submit(PHNO, 7, 3, null, "   ");

        assertNull(f.getComment());
        assertNull(f.getDeliveryRating());
    }

    @Test
    void submitRejectsAnOrderThatIsNotDeliveredOrAlreadyRated() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, PHNO, OrderStatus.SHIPPED)));
        assertThrows(ProductException.class, () -> service.submit(PHNO, 7, 4, null, null));

        when(orders.findById(8L)).thenReturn(Optional.of(order(8, PHNO, OrderStatus.DELIVERED)));
        when(feedback.existsByOrderId(8L)).thenReturn(true);
        assertThrows(ProductException.class, () -> service.submit(PHNO, 8, 4, null, null));
        verify(feedback, never()).save(any());
    }

    @Test
    void anotherCustomersOrIsUnknownOrderIsNotFound() {
        when(orders.findById(7L)).thenReturn(Optional.of(order(7, 9111111111L, OrderStatus.DELIVERED)));
        when(orders.findById(8L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.submit(PHNO, 7, 4, null, null));
        assertThrows(OrderNotFoundException.class, () -> service.submit(PHNO, 8, 4, null, null));
    }

    @Test
    void submitValidatesStarsCommentAndPhone() {
        assertThrows(ProductException.class, () -> service.submit(PHNO, 7, 0, null, null));
        assertThrows(ProductException.class, () -> service.submit(PHNO, 7, 6, null, null));
        assertThrows(ProductException.class, () -> service.submit(PHNO, 7, 3, 9, null));
        assertThrows(ProductException.class, () -> service.submit(PHNO, 7, 3, null, "x".repeat(501)));
        assertThrows(ProductException.class, () -> service.submit(123L, 7, 3, null, null));
    }

    @Test
    void summaryAveragesAndCountsRatings() {
        when(feedback.findAll()).thenReturn(List.of(fb(5, 4), fb(4, null), fb(4, 5)));
        when(feedback.findAllByOrderByIdDesc(any())).thenReturn(List.of());

        FeedbackSummary s = service.summary();

        assertEquals(3, s.totalFeedback());
        assertEquals(4.3, s.averageRating());
        assertEquals(4.5, s.averageDeliveryRating());
        assertEquals(2L, s.ratingCounts().get(4));
        assertEquals(0L, s.ratingCounts().get(1));
    }

    @Test
    void summaryWithNoFeedbackHasNullAverages() {
        when(feedback.findAll()).thenReturn(List.of());
        when(feedback.findAllByOrderByIdDesc(any())).thenReturn(List.of());

        FeedbackSummary s = service.summary();

        assertEquals(0, s.totalFeedback());
        assertNull(s.averageRating());
        assertNull(s.averageDeliveryRating());
    }
}
