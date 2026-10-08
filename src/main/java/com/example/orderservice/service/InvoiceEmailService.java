package com.example.orderservice.service;

import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.InvoiceEmailResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.CustomerAuthException;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Email me this invoice". The receipt always goes to the email the customer verified at sign-in - the caller can't
 * name an address, so this can't be used to push mail at someone else - and the same order can only be sent again
 * after a cooldown, so it can't be used to flood that inbox either. The cooldown is in memory and per instance, like
 * LoginRateLimiter.
 */
@Service
public class InvoiceEmailService {
    private static final Logger log = LoggerFactory.getLogger(InvoiceEmailService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final OrderService orderService;
    private final CustomerAccountRepository accounts;
    private final MailService mailService;
    private final InvoicePdfService pdfService;
    private final Clock clock;
    private final Duration cooldown;
    private final ZoneId zone;
    private final Map<Long, Instant> lastSent = new HashMap<>();

    public InvoiceEmailService(OrderService orderService, CustomerAccountRepository accounts, MailService mailService,
                               InvoicePdfService pdfService, Clock clock, @Value("${invoice.email.cooldown-seconds:60}") long cooldownSeconds,
                               @Value("${digest.zone:Asia/Kolkata}") String zone) {
        this.orderService = orderService;
        this.accounts = accounts;
        this.mailService = mailService;
        this.pdfService = pdfService;
        this.clock = clock;
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
    }

    public InvoiceEmailResult send(long orderId, long phno) {
        Invoice invoice = orderService.getInvoice(orderId, phno); // 404 unless the order belongs to this phone number
        String email = accounts.findById(phno).map(CustomerAccount::getEmail).orElse(null);
        if (email == null || email.isBlank()) {
            throw new CustomerAuthException(HttpStatus.CONFLICT,
                    "There is no verified email on this account yet - sign in with your email first.");
        }
        Instant now = clock.instant();
        synchronized (this) {
            Instant previous = lastSent.get(orderId);
            if (previous != null && previous.plus(cooldown).isAfter(now)) {
                throw new CustomerAuthException(HttpStatus.TOO_MANY_REQUESTS,
                        "This invoice was just emailed - please check your inbox before asking again.");
            }
            lastSent.put(orderId, now);
        }
        if (!deliver(email, invoice)) {
            synchronized (this) {
                lastSent.remove(orderId); // nothing arrived, so don't make them wait to retry
            }
            throw new CustomerAuthException(HttpStatus.BAD_GATEWAY, "We couldn't send the email right now. Please try again.");
        }
        return new InvoiceEmailResult(mask(email));
    }

    // The PDF goes along as an attachment; if it can't be rendered the plain-text invoice still goes out.
    private boolean deliver(String email, Invoice invoice) {
        String subject = "Your Charan Mart invoice for order #" + invoice.orderId();
        byte[] pdf = null;
        try {
            pdf = pdfService.render(invoice);
        } catch (RuntimeException e) {
            log.warn("Could not render the invoice PDF for order #{}; sending the text invoice only", invoice.orderId(), e);
        }
        if (pdf == null) {
            return mailService.send(email, subject, body(invoice, false));
        }
        return mailService.send(email, subject, body(invoice, true), pdfService.fileName(invoice), pdf, "application/pdf");
    }

    String body(Invoice inv) {
        return body(inv, false);
    }

    String body(Invoice inv, boolean pdfAttached) {
        StringBuilder b = new StringBuilder("Hi").append(inv.customerName() == null || inv.customerName().isBlank()
                ? "" : " " + inv.customerName()).append(",\n\nHere is your invoice from Charan Mart")
                .append(pdfAttached ? " (also attached as a PDF).\n\n" : ".\n\n");
        if (inv.invoiceNumber() != null) {
            b.append("Tax invoice ").append(inv.invoiceNumber());
            if (inv.invoiceDate() != null) {
                b.append(", ").append(DATE.format(inv.invoiceDate().atZone(zone)));
            }
            b.append('\n');
        }
        b.append("Order #").append(inv.orderId()).append('\n');
        if (inv.placedAt() != null) {
            b.append("Date: ").append(DATE.format(inv.placedAt().atZone(zone))).append('\n');
        }
        if (inv.shippingAddress() != null) {
            b.append("Ship to: ").append(inv.shippingAddress()).append('\n');
        }
        b.append("Payment: ").append(inv.paymentMethod()).append(inv.paid() ? " (paid)" : " (unpaid)")
                .append(" | Status: ").append(inv.status()).append("\n\n");
        for (Invoice.Line line : inv.lines()) {
            b.append("  ").append(line.quantity()).append(" x ").append(line.productName()).append("  @ Rs. ")
                    .append(money(line.unitPrice())).append(" = Rs. ").append(money(line.lineTotal()));
            if (line.cancelledQuantity() > 0) {
                b.append("  (").append(line.cancelledQuantity()).append(" cancelled)");
            }
            if (line.returnedQuantity() > 0) {
                b.append("  (").append(line.returnedQuantity()).append(" returned)");
            }
            b.append('\n');
        }
        b.append('\n');
        if (inv.discountAmount() > 0) {
            b.append("Coupon").append(inv.couponCode() == null ? "" : " (" + inv.couponCode() + ")")
                    .append(": -Rs. ").append(money(inv.discountAmount())).append('\n');
        }
        if (inv.pointsRedeemed() > 0) {
            b.append("Loyalty points redeemed: -Rs. ").append(money(inv.pointsRedeemed())).append('\n');
        }
        if (inv.storeCreditUsed() > 0) {
            b.append("Store credit used: -Rs. ").append(money(inv.storeCreditUsed())).append('\n');
        }
        b.append("Total charged: Rs. ").append(money(inv.totalPrice())).append('\n');
        if (inv.refundedAmount() > 0) {
            b.append("Refunded or taken off since: Rs. ").append(money(inv.refundedAmount())).append('\n');
        }
        Invoice.Tax tax = inv.tax();
        if (tax != null && !tax.lines().isEmpty()) {
            b.append("\nGST (included in the prices above)");
            if (tax.sellerGstin() != null) {
                b.append(" - seller GSTIN ").append(tax.sellerGstin());
            }
            b.append('\n');
            b.append("  Taxable value: Rs. ").append(money(tax.taxableValue())).append('\n');
            if (tax.interState()) {
                b.append("  IGST: Rs. ").append(money(tax.igst())).append('\n');
            } else {
                b.append("  CGST: Rs. ").append(money(tax.cgst())).append(" | SGST: Rs. ").append(money(tax.sgst())).append('\n');
            }
        }
        if (inv.creditNotes() != null && !inv.creditNotes().isEmpty()) {
            b.append("\nGST credit notes (tax reversed for cancelled or returned items):\n");
            for (var note : inv.creditNotes()) {
                b.append("  ").append(note.number()).append(" - ").append("RETURNED".equals(note.reason()) ? "returned" : "cancelled")
                        .append(", Rs. ").append(money(note.total())).append(" of which GST Rs. ")
                        .append(money(note.cgst() + note.sgst() + note.igst())).append('\n');
            }
        }
        b.append("\nItem prices on older orders show today's catalog price; the total is what this order was actually charged.\n");
        return b.toString();
    }

    // a***@example.com - enough for the customer to recognise the address without echoing it back in full.
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "your email";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
