package com.example.orderservice.service;

import com.example.orderservice.dto.DigestResult;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.RevenueTimeseries;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CartRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The shop owner's morning summary: yesterday's orders and revenue, what is waiting on someone (orders to ship or
 * deliver, unpaid UPI orders, flagged reviews), and what needs restocking. Built from the same figures the admin
 * dashboard already shows (nothing new is stored), emailed to digest.to by DigestScheduler, or on demand through
 * POST /cart/digest/send. Each section is fetched on its own, so one source being down (ProductService) leaves a
 * "not available" line in the email instead of losing the whole digest.
 */
@Service
public class DigestService {
    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final int MAX_LOW_STOCK_LINES = 15;

    private final OrderService orderService;
    private final CartRepository orders;
    private final MailService mailService;
    private final Clock clock;
    private final String to;
    private final ZoneId zone;

    public DigestService(OrderService orderService, CartRepository orders, MailService mailService, Clock clock,
                         @Value("${digest.to:}") String to, @Value("${digest.zone:Asia/Kolkata}") String zone) {
        this.orderService = orderService;
        this.orders = orders;
        this.mailService = mailService;
        this.clock = clock;
        this.to = to == null ? "" : to.trim();
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
    }

    public boolean isConfigured() {
        return !to.isEmpty();
    }

    // The digest as it would be sent right now, without sending it.
    public DigestResult preview() {
        return build(false, isConfigured() ? null : "digest.to (ADMIN_EMAIL) is not set");
    }

    // Builds and emails the digest. sent=false says why (no recipient configured, or the mail server refused it).
    public DigestResult send() {
        if (!isConfigured()) {
            log.info("Daily digest skipped: digest.to (ADMIN_EMAIL) is not set");
            return build(false, "digest.to (ADMIN_EMAIL) is not set");
        }
        DigestResult built = build(false, null);
        boolean sent = mailService.send(to, built.subject(), built.body());
        log.info("Daily digest {} to {}", sent ? "sent" : "FAILED", to);
        return new DigestResult(sent, to, built.subject(), built.body(), sent ? null : "the mail server did not accept it");
    }

    private DigestResult build(boolean sent, String reason) {
        LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
        StringBuilder body = new StringBuilder();
        body.append("Charan Mart - daily digest for ").append(yesterday).append(" (").append(zone.getId()).append(")\n");

        section(body, "Sales", () -> {
            RevenueTimeseries day = orderService.getRevenueTimeseries(yesterday, yesterday, "day", zone.getId());
            RevenueTimeseries week = orderService.getRevenueTimeseries(yesterday.minusDays(6), yesterday, "day", zone.getId());
            return "  Yesterday:   " + day.totalOrders() + " order(s), Rs. " + money(day.totalRevenue()) + "\n"
                    + "  Last 7 days: " + week.totalOrders() + " order(s), Rs. " + money(week.totalRevenue()) + "\n"
                    + "  (revenue is net of refunds; cancelled and unpaid UPI orders are left out)\n";
        });

        section(body, "Waiting on you", () -> {
            int toShip = orders.findByStatus(OrderStatus.PLACED).size();
            int toDeliver = orders.findByStatus(OrderStatus.SHIPPED).size();
            List<Cart> unpaid = orders.findByStatus(OrderStatus.PENDING_PAYMENT);
            int flagged = orderService.getFlaggedReviews(0, 200).size();
            return "  Orders to ship:            " + toShip + "\n"
                    + "  Shipped, to mark delivered: " + toDeliver + "\n"
                    + "  Unpaid UPI orders:         " + unpaid.size() + "\n"
                    + "  Flagged reviews:           " + flagged + (flagged >= 200 ? "+" : "") + "\n";
        });

        section(body, "Restock", () -> {
            List<LowStockItem> low = orderService.getLowStockReport();
            if (low.isEmpty()) {
                return "  Nothing is at or below its low-stock threshold.\n";
            }
            StringBuilder lines = new StringBuilder();
            low.stream().limit(MAX_LOW_STOCK_LINES).forEach(item -> lines.append("  ")
                    .append(item.level().equals("OUT") ? "OUT  " : "LOW  ")
                    .append(item.productName()).append(" - ").append(item.productStock()).append(" left (threshold ")
                    .append(item.lowStockThreshold()).append(")")
                    .append(item.waitlistCount() > 0 ? ", " + item.waitlistCount() + " waiting" : "").append('\n'));
            if (low.size() > MAX_LOW_STOCK_LINES) {
                lines.append("  ... and ").append(low.size() - MAX_LOW_STOCK_LINES).append(" more\n");
            }
            return lines.toString();
        });

        body.append("\nOpen the admin dashboard (cart.html) to act on any of this.\n");
        return new DigestResult(sent, to.isEmpty() ? null : to, "Charan Mart daily digest - " + yesterday, body.toString(), reason);
    }

    private void section(StringBuilder body, String title, Supplier<String> content) {
        body.append('\n').append(title).append('\n');
        try {
            body.append(content.get());
        } catch (RuntimeException e) {
            log.error("Daily digest: {} section failed: {}", title, e.getMessage());
            body.append("  (not available right now)\n");
        }
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
