package com.example.orderservice.service;

import com.example.orderservice.dto.AdminCustomerLookup;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Admin "who is this customer" lookup, by phone number or by the email they signed in with. Read-only; reuses
 * OrderService.getCustomerProfile() for the wishlist/review/loyalty figures so they can never disagree with what
 * the customer sees on their own account page.
 */
@Service
public class AdminCustomerService {
    static final int RECENT_ORDER_LIMIT = 10;

    private final CartRepository orders;
    private final CustomerAccountRepository accounts;
    private final ShippingAddressRepository addresses;
    private final OrderService orderService;
    private final TrackingEventRepository tracking;

    public AdminCustomerService(CartRepository orders, CustomerAccountRepository accounts,
                                ShippingAddressRepository addresses, OrderService orderService,
                                TrackingEventRepository tracking) {
        this.orders = orders;
        this.accounts = accounts;
        this.addresses = addresses;
        this.orderService = orderService;
        this.tracking = tracking;
    }

    // Exactly one of phno / email. An email that no account has bound is a 404, not a guess.
    public AdminCustomerLookup lookup(Long phno, String email) {
        boolean hasPhno = phno != null;
        boolean hasEmail = email != null && !email.isBlank();
        if (hasPhno == hasEmail) {
            throw new ProductException("Provide either phno or email");
        }
        CustomerAccount account;
        long phone;
        if (hasPhno) {
            phone = phno;
            account = accounts.findById(phone).orElse(null);
        } else {
            String wanted = email.trim().toLowerCase(Locale.ROOT);
            account = accounts.findAll().stream()
                    .filter(a -> a.getEmail() != null && a.getEmail().trim().toLowerCase(Locale.ROOT).equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new OrderNotFoundException("No customer is signed in with that email"));
            phone = account.getPhno();
        }
        // Throws "Invalid mobile number" for a malformed phone, same as every other phone-keyed call.
        CustomerProfile profile = orderService.getCustomerProfile(phone);

        List<Cart> all = orders.findBycustomerPhno(phone);
        Map<String, Integer> byStatus = new TreeMap<>();
        double netSpend = 0;
        int standing = 0;
        java.time.Instant firstOrderAt = null;
        java.time.Instant lastOrderAt = null;
        for (Cart order : all) {
            byStatus.merge(String.valueOf(order.getStatus()), 1, Integer::sum);
            netSpend += netPaid(order);
            if (counts(order)) {
                standing++;
                if (order.getOrderId() != null) {
                    java.time.Instant placed = tracking.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                            .map(e -> e.getTimestamp()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                    if (placed != null) {
                        if (firstOrderAt == null || placed.isBefore(firstOrderAt)) {
                            firstOrderAt = placed;
                        }
                        if (lastOrderAt == null || placed.isAfter(lastOrderAt)) {
                            lastOrderAt = placed;
                        }
                    }
                }
            }
        }
        double averageOrderValue = standing == 0 ? 0 : Math.round(netSpend / standing * 100) / 100.0;
        List<AdminCustomerLookup.RecentOrder> recent = all.stream()
                .sorted(Comparator.comparing(Cart::getOrderId).reversed())
                .limit(RECENT_ORDER_LIMIT)
                .map(o -> new AdminCustomerLookup.RecentOrder(o.getOrderId(), String.valueOf(o.getStatus()),
                        String.valueOf(o.getPaymentMethod()), o.isPaid(), o.getTotalPrice(), o.getRefundedAmount()))
                .toList();
        List<AdminCustomerLookup.AddressLine> addressLines = addresses.findByCustomerPhno(phone).stream()
                .map(a -> new AdminCustomerLookup.AddressLine(a.getId(), a.getLabel(), format(a), a.isDefault()))
                .toList();

        return new AdminCustomerLookup(phone,
                account == null ? null : account.getEmail(),
                account == null ? null : account.getVerifiedAt(),
                all.size(), byStatus, Math.round(netSpend * 100) / 100.0,
                profile.wishlistCount(), profile.reviewCount(), profile.loyaltyTier(),
                profile.loyaltyPointsBalance(), profile.lifetimePointsEarned(), recent, addressLines,
                averageOrderValue, firstOrderAt, lastOrderAt);
    }

    // An order still stands unless it was cancelled, returned, or is waiting for payment (null status = pre-status row).
    private static boolean counts(Cart order) {
        OrderStatus status = order.getStatus();
        return status != OrderStatus.CANCELLED && status != OrderStatus.RETURNED && status != OrderStatus.PENDING_PAYMENT;
    }

    private static double netPaid(Cart order) {
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.CANCELLED || status == OrderStatus.RETURNED || status == OrderStatus.PENDING_PAYMENT) {
            return 0;
        }
        return Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
    }

    private static String format(ShippingAddress a) {
        return java.util.stream.Stream.of(a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPincode())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
    }
}
