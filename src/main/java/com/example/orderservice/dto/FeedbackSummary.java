package com.example.orderservice.dto;

import com.example.orderservice.entity.OrderFeedback;

import java.util.List;
import java.util.Map;

// Admin view of all order feedback: overall counts/averages (null averages when there is none yet), how many
// 1..5-star ratings there are, and the most recent entries (with the customer's phone - admin only).
public record FeedbackSummary(long totalFeedback, Double averageRating, Double averageDeliveryRating,
                              Map<Integer, Long> ratingCounts, List<OrderFeedback> recent) {
}
