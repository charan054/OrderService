package com.example.orderservice.service;

import com.example.orderservice.dto.TimelineEntry;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.entity.DeliveryAttempt;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.DeliveryAttemptRepository;
import com.example.orderservice.repository.ExchangeRequestRepository;
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
    private final DeliveryAttemptRepository deliveryAttempts;
    private final ExchangeRequestRepository exchanges;

    public OrderTimelineService(CartRepository orders, TrackingEventRepository tracking,
                                NotificationLogRepository notifications, StoreCreditTransactionRepository credits,
                                DeliveryAttemptRepository deliveryAttempts, ExchangeRequestRepository exchanges) {
        this.exchanges = exchanges;
        this.orders = orders;
        this.tracking = tracking;
        this.notifications = notifications;
        this.credits = credits;
        this.deliveryAttempts = deliveryAttempts;
    }

    public List<TimelineEntry> timeline(long orderId, long phno) {
        Cart order = orders.findById(orderId).filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        List<TimelineEntry> out = new ArrayList<>();
        for (TrackingEvent e : tracking.findByOrderIdOrderByTimestampAsc(orderId)) {
            out.add(new TimelineEntry(e.getTimestamp(), "STATUS", statusTitle(order, e.getStatus())));
        }
        for (NotificationLog n : notifications.findByOrderIdOrderBySentAtAsc(orderId)) {
            if (n.getSentAt() == null || n.getMessage() == null || isStatusMessage(order, n) || isDeliveryAttemptMessage(n)) {
                continue;
            }
            out.add(new TimelineEntry(n.getSentAt(), "UPDATE", n.getMessage()));
        }
        for (DeliveryAttempt a : deliveryAttempts.findByOrderIdOrderByAttemptNoAsc(orderId)) {
            if (a.getCreatedAt() != null) {
                out.add(new TimelineEntry(a.getCreatedAt(), "DELIVERY", "Delivery attempt " + a.getAttemptNo() + " failed: "
                        + (a.getReason() == null ? "" : a.getReason().toLowerCase(Locale.ROOT).replace('_', ' '))));
            }
        }
        for (com.example.orderservice.entity.ExchangeRequest x : exchanges.findByOrderIdOrderByIdAsc(orderId)) {
            String what = x.getQuantity() + " x product #" + x.getProductId() + " for product #" + x.getReplacementProductId();
            if (x.getCreatedAt() != null) {
                out.add(new TimelineEntry(x.getCreatedAt(), "EXCHANGE", "Exchange requested: " + what));
            }
            String step = switch (x.getStatus() == null ? "" : x.getStatus()) {
                case "APPROVED" -> "Exchange approved - replacement on its way";
                case "RECEIVED" -> "Exchange complete - original item received";
                case "REJECTED" -> "Exchange not approved";
                case "CANCELLED" -> "Exchange withdrawn";
                default -> null;
            };
            if (step != null && x.getUpdatedAt() != null) {
                out.add(new TimelineEntry(x.getUpdatedAt(), "EXCHANGE", step));
            }
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

    // The email a failed delivery attempt sent (DeliveryFailureService); the attempt itself is shown instead.
    private static boolean isDeliveryAttemptMessage(NotificationLog n) {
        return n.getMessage().startsWith("We could not deliver order #")
                // the emails an exchange sent (ExchangeService); the exchange's own entries are shown instead
                || n.getMessage().startsWith("Exchange requested for order #")
                || n.getMessage().startsWith("Your exchange for order #");
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
