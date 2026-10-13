package com.example.orderservice.service;

import com.example.orderservice.dto.TimelineEntry;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One chronological story per order, built only from what is already recorded: the status changes (tracking events),
 * the order updates and return/pickup steps the customer was told about (their notifications - a status notification is
 * skipped because its tracking event already says it), and refunds that landed in store credit. A refund to PhonePe has
 * no timestamp of its own; the cancel/return step just before it covers it, and the refund summary says where it went.
 */
@Service
public class OrderTimelineService {
    private final CartRepository orders;
    private final TrackingEventRepository tracking;
    private final NotificationLogRepository notifications;
    private final StoreCreditTransactionRepository credits;

    public OrderTimelineService(CartRepository orders, TrackingEventRepository tracking,
                                NotificationLogRepository notifications, StoreCreditTransactionRepository credits) {
        this.orders = orders;
        this.tracking = tracking;
        this.notifications = notifications;
        this.credits = credits;
    }

    public List<TimelineEntry> timeline(long orderId, long phno) {
        Cart order = orders.findById(orderId).filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        List<TimelineEntry> out = new ArrayList<>();
        for (TrackingEvent e : tracking.findByOrderIdOrderByTimestampAsc(orderId)) {
            out.add(new TimelineEntry(e.getTimestamp(), "STATUS", statusTitle(order, e.getStatus())));
        }
        for (NotificationLog n : notifications.findByOrderIdOrderBySentAtAsc(orderId)) {
            if (n.getSentAt() == null || n.getMessage() == null || isStatusMessage(order, n)) {
                continue;
            }
            out.add(new TimelineEntry(n.getSentAt(), "UPDATE", n.getMessage()));
        }
        for (StoreCreditTransaction t : credits.findByOrderIdAndTypeOrderByIdAsc(orderId, StoreCreditTransaction.Type.REFUND)) {
            if (t.getCreatedAt() != null) {
                out.add(new TimelineEntry(t.getCreatedAt(), "REFUND",
                        "Rs. " + String.format(Locale.ROOT, "%.2f", t.getAmount()) + " added to your store credit"));
            }
        }
        out.sort(Comparator.comparing(TimelineEntry::at));
        return out;
    }

    // A notification the status change already produced (same wording the notifier used), so the tracking event stands for it.
    private static boolean isStatusMessage(Cart order, NotificationLog n) {
        return n.getEventType() != null && n.getMessage().equals(CustomerNotifier.subjectFor(order, n.getEventType()));
    }

    private static String statusTitle(Cart order, OrderStatus status) {
        if (status == null) {
            return "Order updated";
        }
        return switch (status) {
            case PLACED -> "Order placed";
            case SHIPPED -> "Shipped" + (order.getCarrier() == null || order.getCarrier().isBlank() ? "" : " via " + order.getCarrier());
            case DELIVERED -> "Delivered";
            case CANCELLED -> "Cancelled";
            case RETURNED -> "Return complete";
            default -> "Order " + status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
