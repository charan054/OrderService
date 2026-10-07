package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;

/**
 * Tells a customer their order moved on its own (ship/deliver happen on the warehouse's schedule, unlike
 * placing/cancelling/returning, which the customer just did themselves). Emails the address the customer verified
 * at storefront sign-in (see CustomerAuthService) and records a NotificationLog row either way, so "My
 * notifications" works even for a customer who has never signed in and so has no email on file.
 *
 * Called directly from OrderService rather than off the Kafka topic: no broker is reachable in every environment
 * this runs in, and a customer email shouldn't depend on one being up. The Kafka message is still published.
 */
@Service
public class CustomerNotifier {
    private static final Logger log = LoggerFactory.getLogger(CustomerNotifier.class);

    private final CustomerAccountRepository accounts;
    private final NotificationLogRepository notifications;
    private final MailService mailService;
    private final Clock clock;

    public CustomerNotifier(CustomerAccountRepository accounts, NotificationLogRepository notifications,
                            MailService mailService, Clock clock) {
        this.accounts = accounts;
        this.notifications = notifications;
        this.mailService = mailService;
        this.clock = clock;
    }

    public NotificationLog notifyStatusChange(Cart order, OrderStatus status) {
        return notifyStatusChange(order, status, null);
    }

    // detail is an optional extra line for the email body (e.g. why an unpaid order was cancelled).
    public NotificationLog notifyStatusChange(Cart order, OrderStatus status, String detail) {
        return deliver(order, status, subjectFor(order, status), bodyFor(order, status, detail));
    }

    // A partial cancel/return: some units of one item were taken off the order and refunded. The record keeps the
    // order's current status as its event type (there is no separate status for "part of it changed").
    public NotificationLog notifyItemRefund(Cart order, String what, int productId, int quantity, double refunded) {
        String subject = "Order #" + order.getOrderId() + ": " + quantity + " x product #" + productId + " " + what;
        String name = order.getCustomerName() == null || order.getCustomerName().isBlank() ? "there" : order.getCustomerName();
        String refundLine = refunded > 0
                ? refundLine(order, refunded) + "\n"
                : "Nothing was charged for these items, so there is nothing to refund.\n";
        String body = "Hi " + name + ",\n\n" + quantity + " x product #" + productId + " from order #" + order.getOrderId()
                + " was " + what + ".\n\n" + refundLine + "\nThe rest of your order is unchanged.\n";
        return deliver(order, order.getStatus(), subject, body);
    }

    private NotificationLog deliver(Cart order, OrderStatus status, String subject, String body) {
        String email = accounts.findById(order.getCustomerPhno()).map(CustomerAccount::getEmail).orElse(null);
        boolean emailed = false;
        if (email != null && !email.isBlank()) {
            emailed = mailService.send(email, subject, body);
        }

        NotificationLog notification = new NotificationLog();
        notification.setOrderId(order.getOrderId());
        notification.setEventType(status);
        notification.setMessage(subject);
        notification.setSentAt(clock.instant());
        notification.setEmailed(emailed);
        log.info("Customer notification for order {} ({}): {}", order.getOrderId(), status,
                emailed ? "emailed" : email == null ? "recorded only, no verified email on file" : "recorded only, email failed");
        return notifications.save(notification);
    }

    static String subjectFor(Cart order, OrderStatus status) {
        return switch (status) {
            case PLACED -> "Your order #" + order.getOrderId() + " is confirmed";
            case SHIPPED -> "Your order #" + order.getOrderId() + " has shipped";
            case DELIVERED -> "Your order #" + order.getOrderId() + " was delivered";
            case CANCELLED -> "Your order #" + order.getOrderId() + " was cancelled";
            case RETURNED -> "Your return for order #" + order.getOrderId() + " is complete";
            default -> "Your order #" + order.getOrderId() + " is now " + status.name().toLowerCase(Locale.ROOT);
        };
    }

    static String bodyFor(Cart order, OrderStatus status) {
        return bodyFor(order, status, null);
    }

    static String bodyFor(Cart order, OrderStatus status, String detail) {
        String name = order.getCustomerName() == null || order.getCustomerName().isBlank() ? "there" : order.getCustomerName();
        String total = String.format(Locale.ROOT, "%.2f", order.getTotalPrice());
        boolean cashDue = order.getPaymentMethod() != null && order.getPaymentMethod().name().equals("CASH") && !order.isPaid();
        boolean stillComing = status == OrderStatus.PLACED || status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
        String cashNote = cashDue && stillComing
                ? "\nPayment: cash on delivery - please keep Rs. " + total + " ready.\n" : "\n";
        String lead = switch (status) {
            case PLACED -> "Thanks for your order #" + order.getOrderId() + " - we have it and will pack it shortly."
                    + (order.getDiscountAmount() > 0
                    ? "\nYou saved Rs. " + String.format(Locale.ROOT, "%.2f", order.getDiscountAmount()) + " with your coupon." : "");
            case SHIPPED -> "Good news - your order #" + order.getOrderId() + " is on its way. It usually arrives within 3 days."
                    + shipmentLine(order);
            case DELIVERED -> "Your order #" + order.getOrderId() + " has been delivered. Thanks for shopping with us!";
            case CANCELLED -> "Your order #" + order.getOrderId() + " has been cancelled."
                    + (detail == null || detail.isBlank() ? "" : "\nReason: " + detail + ".")
                    + "\n" + (order.getRefundedAmount() > 0 ? refundLine(order, order.getRefundedAmount())
                    : "You were not charged for this order.");
            case RETURNED -> "We have received your return for order #" + order.getOrderId() + "."
                    + (order.getReturnReason() == null || order.getReturnReason().isBlank() ? "" : "\nReason given: " + order.getReturnReason() + ".")
                    + "\n" + (order.getRefundedAmount() > 0 ? refundLine(order, order.getRefundedAmount())
                    : "Nothing was charged for this order, so there is nothing to refund.");
            default -> "Your order #" + order.getOrderId() + " is now " + status.name().toLowerCase(Locale.ROOT) + ".";
        };
        return "Hi " + name + ",\n\n" + lead + "\n\nOrder total: Rs. " + total + cashNote
                + "\nYou can follow every step under \"My notifications\" and \"My account\" in the shop.\n";
    }

    // "Shipped via X, tracking number Y." - whichever of the two was recorded; empty when neither was.
    private static String shipmentLine(Cart order) {
        boolean hasCarrier = order.getCarrier() != null && !order.getCarrier().isBlank();
        boolean hasTracking = order.getTrackingNumber() != null && !order.getTrackingNumber().isBlank();
        if (!hasCarrier && !hasTracking) {
            return "";
        }
        return "\n" + (hasCarrier ? "Carrier: " + order.getCarrier() : "")
                + (hasCarrier && hasTracking ? ", " : "")
                + (hasTracking ? "tracking number: " + order.getTrackingNumber() : "") + ".";
    }

    private static String refundLine(Cart order, double amount) {
        String formatted = String.format(Locale.ROOT, "%.2f", amount);
        boolean cash = order.getPaymentMethod() != null && order.getPaymentMethod().name().equals("CASH");
        return cash ? "Rs. " + formatted + " is no longer due." : "Rs. " + formatted + " has been refunded to your PhonePe account.";
    }
}
