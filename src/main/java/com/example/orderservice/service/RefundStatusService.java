package com.example.orderservice.service;

import com.example.orderservice.dto.RefundStatus;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Tells a customer where the money of a cancelled or returned order went. Nothing new is stored: the split comes from
 * what the order already records (refundedAmount, storeCreditRefunded) and its store-credit REFUND transactions.
 * A PhonePe refund is only ever recorded once PhonepayService has completed it, so it is already with the buyer.
 */
@Service
public class RefundStatusService {
    private final CartRepository orders;
    private final StoreCreditTransactionRepository creditTransactions;
    private final TrackingEventRepository tracking;

    public RefundStatusService(CartRepository orders, StoreCreditTransactionRepository creditTransactions,
                               TrackingEventRepository tracking) {
        this.orders = orders;
        this.creditTransactions = creditTransactions;
        this.tracking = tracking;
    }

    public RefundStatus statusOf(long orderId, long phno) {
        Cart order = orders.findById(orderId)
                .filter(o -> o.getCustomerPhno() == phno)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        List<StoreCreditTransaction> credits = creditTransactions.findByOrderIdAndTypeOrderByIdAsc(orderId, StoreCreditTransaction.Type.REFUND);
        double creditTotal = credits.stream().mapToDouble(StoreCreditTransaction::getAmount).sum();
        double usedBack = Math.min(creditTotal, order.getStoreCreditRefunded());
        double refunded = money(order.getRefundedAmount());
        // Credits beyond the store credit the order was paid with are refunds of the money paid (chosen as credit, or cash collected).
        double refundAsCredit = Math.min(refunded, money(Math.max(0, creditTotal - usedBack)));
        double rest = money(refunded - refundAsCredit);
        boolean cash = order.getPaymentMethod() == PaymentMethod.CASH;
        double toPhonePe = cash ? 0 : rest;
        double noLongerDue = cash ? rest : 0;

        Instant last = tracking.findByOrderIdOrderByTimestampAsc(orderId).stream()
                .filter(e -> e.getStatus() == OrderStatus.CANCELLED || e.getStatus() == OrderStatus.RETURNED)
                .map(TrackingEvent::getTimestamp)
                .reduce((a, b) -> b).orElse(null);
        List<RefundStatus.Entry> entries = credits.stream()
                .map(t -> new RefundStatus.Entry(t.getCreatedAt(), t.getAmount(), t.getNote()))
                .toList();
        return new RefundStatus(orderId, String.valueOf(order.getStatus()), String.valueOf(order.getPaymentMethod()),
                money(order.getTotalPrice()), refunded, toPhonePe, refundAsCredit, noLongerDue, money(usedBack), last, entries,
                summary(order, refunded, toPhonePe, refundAsCredit, noLongerDue, usedBack));
    }

    private static String summary(Cart order, double refunded, double toPhonePe, double toCredit, double noLongerDue, double usedBack) {
        if (refunded <= 0 && usedBack <= 0) {
            return order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.RETURNED
                    ? "Nothing was charged for this order, so there is nothing to refund."
                    : "No refund on this order.";
        }
        StringBuilder text = new StringBuilder();
        if (toPhonePe > 0) {
            text.append("Rs. ").append(rupees(toPhonePe)).append(" was refunded to your PhonePe account (completed). ");
        }
        if (toCredit > 0) {
            text.append("Rs. ").append(rupees(toCredit)).append(" was added to your store credit. ");
        }
        if (noLongerDue > 0) {
            text.append("Rs. ").append(rupees(noLongerDue)).append(" is no longer due - you had not paid it yet. ");
        }
        if (usedBack > 0) {
            text.append("Rs. ").append(rupees(usedBack)).append(" of store credit you used was given back. ");
        }
        return text.toString().trim();
    }

    private static String rupees(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static double money(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
