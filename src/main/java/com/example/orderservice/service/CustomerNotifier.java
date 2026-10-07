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
        String subject = subjectFor(order, status);
        String body = bodyFor(order, status);

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
            case SHIPPED -> "Your order #" + order.getOrderId() + " has shipped";
            case DELIVERED -> "Your order #" + order.getOrderId() + " was delivered";
            default -> "Your order #" + order.getOrderId() + " is now " + status.name().toLowerCase(Locale.ROOT);
        };
    }

    static String bodyFor(Cart order, OrderStatus status) {
        String name = order.getCustomerName() == null || order.getCustomerName().isBlank() ? "there" : order.getCustomerName();
        String total = String.format(Locale.ROOT, "%.2f", order.getTotalPrice());
        String cashNote = order.getPaymentMethod() != null && order.getPaymentMethod().name().equals("CASH") && !order.isPaid()
                ? "\nPayment: cash on delivery - please keep Rs. " + total + " ready.\n" : "\n";
        String lead = switch (status) {
            case SHIPPED -> "Good news - your order #" + order.getOrderId() + " is on its way. It usually arrives within 3 days.";
            case DELIVERED -> "Your order #" + order.getOrderId() + " has been delivered. Thanks for shopping with us!";
            default -> "Your order #" + order.getOrderId() + " is now " + status.name().toLowerCase(Locale.ROOT) + ".";
        };
        return "Hi " + name + ",\n\n" + lead + "\n\nOrder total: Rs. " + total + cashNote
                + "\nYou can follow every step under \"My notifications\" and \"My account\" in the shop.\n";
    }
}
