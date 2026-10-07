package com.example.orderservice.service;

import com.example.orderservice.dto.FeedbackSummary;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderFeedback;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderFeedbackRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Customers rate an order once it has been delivered; admins read the rollup. */
@Service
public class OrderFeedbackService {
    static final int MAX_COMMENT_LENGTH = 500;
    static final int RECENT_LIMIT = 20;

    private final OrderFeedbackRepository feedback;
    private final CartRepository orders;
    private final Clock clock;

    public OrderFeedbackService(OrderFeedbackRepository feedback, CartRepository orders, Clock clock) {
        this.feedback = feedback;
        this.orders = orders;
        this.clock = clock;
    }

    public OrderFeedback submit(long phno, long orderId, int rating, Integer deliveryRating, String comment) {
        validatePhno(phno);
        checkStars(rating, "Rating");
        if (deliveryRating != null) {
            checkStars(deliveryRating, "Delivery rating");
        }
        String text = comment == null ? null : comment.trim();
        if (text != null && text.isEmpty()) {
            text = null;
        }
        if (text != null && text.length() > MAX_COMMENT_LENGTH) {
            throw new ProductException("Comment must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        // Someone else's order, or one that doesn't exist, look identical (404) so ids can't be probed.
        Cart order = orders.findById(orderId)
                .filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ProductException("You can rate an order once it has been delivered");
        }
        if (feedback.existsByOrderId(orderId)) {
            throw new ProductException("You have already rated this order");
        }
        OrderFeedback f = new OrderFeedback();
        f.setOrderId(orderId);
        f.setCustomerPhno(phno);
        f.setRating(rating);
        f.setDeliveryRating(deliveryRating);
        f.setComment(text);
        f.setCreatedAt(Instant.now(clock));
        return feedback.save(f);
    }

    public List<OrderFeedback> mine(long phno) {
        validatePhno(phno);
        return feedback.findByCustomerPhnoOrderByIdDesc(phno);
    }

    public FeedbackSummary summary() {
        List<OrderFeedback> all = feedback.findAll();
        Map<Integer, Long> counts = new TreeMap<>();
        for (int stars = 1; stars <= 5; stars++) {
            counts.put(stars, 0L);
        }
        double ratingSum = 0;
        double deliverySum = 0;
        int deliveryCount = 0;
        for (OrderFeedback f : all) {
            counts.merge(f.getRating(), 1L, Long::sum);
            ratingSum += f.getRating();
            if (f.getDeliveryRating() != null) {
                deliverySum += f.getDeliveryRating();
                deliveryCount++;
            }
        }
        Double avg = all.isEmpty() ? null : round1(ratingSum / all.size());
        Double deliveryAvg = deliveryCount == 0 ? null : round1(deliverySum / deliveryCount);
        List<OrderFeedback> recent = feedback.findAllByOrderByIdDesc(PageRequest.of(0, RECENT_LIMIT));
        return new FeedbackSummary(all.size(), avg, deliveryAvg, counts, recent);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static void checkStars(int value, String what) {
        if (value < 1 || value > 5) {
            throw new ProductException(what + " must be between 1 and 5");
        }
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
