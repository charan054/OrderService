package com.example.orderservice.service;

import com.example.orderservice.dto.LowStockAlertResult;
import com.example.orderservice.dto.LowStockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emails the shop owner when products NEWLY drop to or below their low-stock threshold, so the morning digest isn't
 * the only place a sell-out shows up. Remembers (in memory) which products it has already alerted about and only
 * mentions a product again after it has recovered above its threshold and dropped again - otherwise a product that
 * sits at 0 would be emailed every run. After a restart the memory is empty, so one catch-up email lists whatever
 * is currently low. Goes to digest.to (ADMIN_EMAIL); does nothing while that is unset.
 */
@Service
public class LowStockAlertService {
    private static final Logger log = LoggerFactory.getLogger(LowStockAlertService.class);

    private final OrderService orderService;
    private final MailService mailService;
    private final String to;
    private final Set<Integer> alerted = new HashSet<>();

    public LowStockAlertService(OrderService orderService, MailService mailService,
                                @Value("${digest.to:}") String to) {
        this.orderService = orderService;
        this.mailService = mailService;
        this.to = to == null ? "" : to.trim();
    }

    public boolean isConfigured() {
        return !to.isEmpty();
    }

    public synchronized LowStockAlertResult run() {
        if (!isConfigured()) {
            return new LowStockAlertResult(false, 0, "ADMIN_EMAIL (digest.to) is not set");
        }
        List<LowStockItem> low = orderService.getLowStockReport();
        Set<Integer> lowIds = low.stream().map(LowStockItem::productId).collect(Collectors.toSet());
        // A product that has recovered is forgotten, so its next drop alerts again.
        alerted.retainAll(lowIds);
        List<LowStockItem> fresh = low.stream().filter(i -> !alerted.contains(i.productId())).toList();
        if (fresh.isEmpty()) {
            return new LowStockAlertResult(false, 0, "nothing new is low on stock");
        }
        StringBuilder body = new StringBuilder("These products just dropped to or below their low-stock threshold:\n\n");
        for (LowStockItem i : fresh) {
            body.append("  #").append(i.productId()).append(" ").append(i.productName()).append(" - ")
                    .append(i.level().equals("OUT") ? "OUT OF STOCK" : i.productStock() + " left")
                    .append(" (threshold ").append(i.lowStockThreshold()).append(")");
            if (i.waitlistCount() > 0) {
                body.append(", ").append(i.waitlistCount()).append(" customer(s) waiting");
            }
            body.append("\n");
        }
        body.append("\nFull list: GET /cart/lowstock\n");
        boolean sent = mailService.send(to, "Charan Mart: " + fresh.size() + " product(s) low on stock", body.toString());
        if (sent) {
            fresh.forEach(i -> alerted.add(i.productId()));
        }
        log.info("Low-stock alert {} for {} product(s)", sent ? "sent" : "FAILED", fresh.size());
        return new LowStockAlertResult(sent, fresh.size(), sent ? null : "the mail server did not accept it");
    }
}
