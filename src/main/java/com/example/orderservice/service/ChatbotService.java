package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.Faq;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentAttempt;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.FaqRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The storefront's help chat. No AI model: it recognises what the customer is asking from keywords, answers from their own
 * live order and payment data (never anyone else's), falls back to the Help entries, and otherwise points to a support request.
 * It only explains and looks things up - cancelling, returning and paying stay on their own screens, where the customer
 * confirms them.
 */
@Service
public class ChatbotService {
    static final int MAX_MESSAGE = 300;
    private static final Pattern ORDER_ID = Pattern.compile("(?:#|order\\s*(?:no\\.?|number|id)?\\s*)(\\d{1,9})");
    private static final Set<String> STOP = Set.of("the", "and", "for", "you", "your", "can", "how", "what", "when", "where", "does", "did",
            "have", "has", "with", "this", "that", "from", "are", "was", "will", "not", "get", "why", "who", "but", "any", "our", "its");

    public record Reply(String text, List<String> suggestions) {
    }

    private static final List<String> MENU = List.of("Where is my order?", "Cancel an order", "Refund status", "Payment problem", "My loyalty points");

    private final CartRepository orders;
    private final FaqRepository faqs;
    private final PaymentAttemptService paymentAttempts;
    private final OrderService orderService;

    public ChatbotService(CartRepository orders, FaqRepository faqs, PaymentAttemptService paymentAttempts, OrderService orderService) {
        this.orders = orders;
        this.faqs = faqs;
        this.paymentAttempts = paymentAttempts;
        this.orderService = orderService;
    }

    public Reply ask(long phno, String message) {
        if (message == null || message.isBlank()) {
            throw new ProductException("Type a question first");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new ProductException("Please keep your question under " + MAX_MESSAGE + " characters");
        }
        String m = message.toLowerCase(Locale.ROOT).trim();
        List<Cart> mine = new ArrayList<>(orders.findBycustomerPhno(phno));
        mine.sort(Comparator.comparing(Cart::getOrderId, Comparator.nullsLast(Comparator.reverseOrder())));

        if (has(m, "human", "agent", "real person", "complain", "speak to", "talk to", "customer care", "call me")) {
            return new Reply("I'm a simple helper, so for a person please open the order in My account and choose Report a problem - "
                    + "our team answers there and you will see the reply in the shop.", MENU);
        }
        if (has(m, "charged", "deducted", "debited", "money gone", "payment failed", "payment problem", "paid but", "double charge", "payment issue", "payment not")) {
            return payment(phno, mine);
        }
        if (has(m, "refund", "money back", "return", "exchange")) {
            return refund(m, mine);
        }
        if (has(m, "cancel")) {
            return cancel(m, mine);
        }
        if (has(m, "point", "loyalty", "reward", "tier")) {
            return loyalty(phno);
        }
        if (has(m, "where is", "where's", "status", "track", "shipped", "arrive", "when will", "when is", "my order", "order #", "how long", "delivery date", "delivery time")) {
            return status(m, mine);
        }
        if (has(m, "hello", "hi", "hey", "help", "start", "menu")) {
            return new Reply("Hi! I can check your orders, refunds, payments and loyalty points. What would you like to know?", MENU);
        }
        return fromHelp(m);
    }

    private static boolean has(String m, String... words) {
        for (String w : words) {
            // short words must match a whole word ("hi" must not match "ship")
            if (w.length() <= 3 ? Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(m).find() : m.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Cart> pick(String m, List<Cart> mine) {
        Matcher id = ORDER_ID.matcher(m);
        if (id.find()) {
            long wanted = Long.parseLong(id.group(1));
            return mine.stream().filter(o -> o.getOrderId() != null && o.getOrderId() == wanted).findFirst();
        }
        return mine.stream().findFirst();
    }

    private static boolean asked(String m) {
        return ORDER_ID.matcher(m).find();
    }

    private Reply status(String m, List<Cart> mine) {
        if (mine.isEmpty()) {
            return new Reply("I can't find any orders on this number yet. Once you place one, ask me again and I'll track it.", MENU);
        }
        Optional<Cart> order = pick(m, mine);
        if (order.isEmpty()) {
            return new Reply("I can't find that order on your account. Check the number under My account.", MENU);
        }
        Cart o = order.get();
        StringBuilder text = new StringBuilder("Order #" + o.getOrderId() + " is " + label(o.getStatus()) + ".");
        if (o.getStatus() == OrderStatus.PLACED) {
            text.append(" We are getting it ready to ship.");
        }
        if (o.getStatus() == OrderStatus.SHIPPED) {
            text.append(o.getCarrier() == null || o.getCarrier().isBlank() ? "" : " Carrier: " + o.getCarrier() + ".")
                    .append(o.getTrackingNumber() == null || o.getTrackingNumber().isBlank() ? "" : " Tracking number: " + o.getTrackingNumber() + ".");
            if (o.getExpectedDeliveryDate() != null) {
                text.append(" Expected by ").append(o.getExpectedDeliveryDate()).append(".");
            }
            if (o.getDelayReason() != null && !o.getDelayReason().isBlank()) {
                text.append(" It is running late: ").append(o.getDelayReason()).append(".");
            }
        }
        if (o.getStatus() == OrderStatus.PLACED && o.getExpectedDeliveryDate() != null) {
            text.append(" Expected by ").append(o.getExpectedDeliveryDate()).append(".");
        }
        if (!asked(m) && mine.size() > 1) {
            text.append("\n(This is your latest order. Ask \"order #").append(mine.get(1).getOrderId()).append("\" for an earlier one.)");
        }
        return new Reply(text.toString(), List.of("Cancel an order", "Refund status", "Payment problem"));
    }

    private Reply cancel(String m, List<Cart> mine) {
        Optional<Cart> order = pick(m, mine);
        if (order.isEmpty()) {
            return new Reply(mine.isEmpty() ? "You have no orders to cancel." : "I can't find that order on your account.", MENU);
        }
        Cart o = order.get();
        Map<String, Integer> policy = orderService.getCancelPolicy();
        String how = "Open My account, find the order and choose Cancel.";
        String text = switch (o.getStatus()) {
            case PLACED, PENDING_PAYMENT -> "Order #" + o.getOrderId() + " is still " + label(o.getStatus()) + ", so it can be cancelled. " + how
                    + (policy.get("freeMinutes") > 0 ? " Cancelling within " + policy.get("freeMinutes") + " minutes of ordering is free." : "")
                    + " Any payment is refunded to where it came from.";
            case SHIPPED -> "Order #" + o.getOrderId() + " has already shipped, so it can't be cancelled now. "
                    + "Once it is delivered you can return it from My account (within 7 days).";
            case DELIVERED -> "Order #" + o.getOrderId() + " was delivered, so it can't be cancelled. You can return or exchange it from My account within 7 days of delivery.";
            default -> "Order #" + o.getOrderId() + " is " + label(o.getStatus()) + ", so there is nothing to cancel.";
        };
        return new Reply(text, List.of("Refund status", "Where is my order?"));
    }

    private Reply refund(String m, List<Cart> mine) {
        Optional<Cart> order = asked(m) ? pick(m, mine) : mine.stream().filter(o -> o.getRefundedAmount() > 0 || o.getStoreCreditRefunded() > 0
                || o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.RETURNED).findFirst();
        if (order.isEmpty()) {
            return new Reply("I don't see a cancelled or returned order with a refund on your account. To return something delivered "
                    + "in the last 7 days, open My account and choose Return on that order.", MENU);
        }
        Cart o = order.get();
        StringBuilder text = new StringBuilder("Order #" + o.getOrderId() + " is " + label(o.getStatus()) + ".");
        if (o.getRefundedAmount() > 0) {
            text.append(String.format(Locale.ROOT, " Refunded: Rs. %.2f", o.getRefundedAmount()));
            text.append(o.getRefundDestination() == null || o.getRefundDestination().isBlank() ? "." : " to " + o.getRefundDestination() + ".");
        }
        if (o.getStoreCreditRefunded() > 0) {
            text.append(String.format(Locale.ROOT, " Store credit returned: Rs. %.2f.", o.getStoreCreditRefunded()));
        }
        if (o.getRefundedAmount() <= 0 && o.getStoreCreditRefunded() <= 0) {
            text.append(o.isPaid() ? " I don't see a refund recorded yet - if it was cancelled or returned a while ago, report it under My account."
                    : " Nothing was charged for this order, so there is nothing to refund.");
        }
        return new Reply(text.toString(), List.of("Payment problem", "Where is my order?"));
    }

    private Reply payment(long phno, List<Cart> mine) {
        List<PaymentAttempt> stuck = paymentAttempts.needingAttention().stream().filter(a -> a.getCustomerPhno() == phno).toList();
        if (!stuck.isEmpty()) {
            PaymentAttempt a = stuck.get(0);
            return new Reply(String.format(Locale.ROOT, "I can see a payment of Rs. %.2f from %s that did not turn into an order. Our team has been flagged to check it "
                    + "and refund you if you were charged. If it is still not sorted in a day, report it under My account.", a.getAmount(),
                    a.getCreatedAt() == null ? "recently" : a.getCreatedAt().toString().substring(0, 10)), MENU);
        }
        Optional<Cart> unpaid = mine.stream().filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT).findFirst();
        if (unpaid.isPresent()) {
            return new Reply("Order #" + unpaid.get().getOrderId() + " is waiting for your payment. Approve the request in your UPI app before it expires, "
                    + "or the order is cancelled and nothing is charged.", MENU);
        }
        return new Reply("I don't see any payment of yours that is stuck. A payment that failed is not charged; if your bank shows a debit, "
                + "it is normally returned within a few working days. If it isn't, open the order in My account and choose Report a problem.", MENU);
    }

    private Reply loyalty(long phno) {
        LoyaltyAccount account = orderService.getLoyaltyAccount(phno);
        String text = "You have " + account.getPointsBalance() + " loyalty point(s) (1 point = Rs. 1 at checkout). Your tier is " + account.getTier() + ".";
        if (account.getPointsToNextTier() != null) {
            text += " " + account.getPointsToNextTier() + " more points earned takes you to the next tier.";
        }
        return new Reply(text, List.of("Where is my order?", "Refund status"));
    }

    private Reply fromHelp(String m) {
        Set<String> words = new java.util.HashSet<>();
        for (String w : m.split("[^a-z0-9]+")) {
            if (w.length() > 2 && !STOP.contains(w)) {
                words.add(w);
            }
        }
        Faq best = null;
        int bestScore = 0;
        for (Faq faq : faqs.findAllByOrderBySortOrderAscIdAsc()) {
            String haystack = ((faq.getQuestion() == null ? "" : faq.getQuestion()) + " " + (faq.getAnswer() == null ? "" : faq.getAnswer())).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : words) {
                if (haystack.contains(w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = faq;
                bestScore = score;
            }
        }
        // at least half of what was asked has to appear in the entry, so one common word does not pick a random answer
        if (best != null && bestScore >= 1 && bestScore * 2 >= words.size()) {
            return new Reply(best.getAnswer(), MENU);
        }
        return new Reply("Sorry, I didn't understand that. I can check your orders, refunds, payments and points - or open the order in "
                + "My account and choose Report a problem to reach our team.", MENU);
    }

    private static String label(OrderStatus status) {
        return status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
