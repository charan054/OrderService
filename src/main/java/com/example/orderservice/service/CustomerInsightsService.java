package com.example.orderservice.service;

import com.example.orderservice.dto.CustomerInsights;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * New-vs-repeat customers, average order value and top customers. In-memory over every order, the same shape the
 * other admin analytics here use; fine at this scale, and the place to push into SQL if it ever isn't.
 */
@Service
public class CustomerInsightsService {
    static final int MAX_TOP = 50;
    private static final Set<OrderStatus> STANDING = Set.of(OrderStatus.PLACED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    private final CartRepository orders;

    public CustomerInsightsService(CartRepository orders) {
        this.orders = orders;
    }

    private static final class Tally {
        String name;
        long newestOrderId = Long.MIN_VALUE;
        int orders;
        double net;
    }

    public CustomerInsights insights(int top) {
        if (top < 1 || top > MAX_TOP) {
            throw new ProductException("top must be between 1 and " + MAX_TOP);
        }
        Map<Long, Tally> byCustomer = new HashMap<>();
        int totalOrders = 0;
        double netRevenue = 0;
        for (Cart order : orders.findAll()) {
            // Set.of(...).contains(null) throws, and some old rows predate the status column.
            if (order.getStatus() == null || !STANDING.contains(order.getStatus())) {
                continue;
            }
            double net = Math.max(0, order.getTotalPrice() - order.getRefundedAmount());
            Tally t = byCustomer.computeIfAbsent(order.getCustomerPhno(), k -> new Tally());
            t.orders++;
            t.net += net;
            // The name on the customer's most recent order is the one shown.
            long id = order.getOrderId() == null ? Long.MIN_VALUE + 1 : order.getOrderId();
            if (id >= t.newestOrderId) {
                t.newestOrderId = id;
                t.name = order.getCustomerName();
            }
            totalOrders++;
            netRevenue += net;
        }
        int customers = byCustomer.size();
        int repeat = (int) byCustomer.values().stream().filter(t -> t.orders >= 2).count();
        List<CustomerInsights.TopCustomer> topCustomers = byCustomer.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Tally>>comparingDouble(e -> e.getValue().net).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(top)
                .map(e -> new CustomerInsights.TopCustomer(e.getKey(), e.getValue().name, e.getValue().orders,
                        round2(e.getValue().net)))
                .toList();
        return new CustomerInsights(customers, customers - repeat, repeat,
                customers == 0 ? null : round1(100.0 * repeat / customers),
                totalOrders, round2(netRevenue),
                totalOrders == 0 ? null : round2(netRevenue / totalOrders),
                customers == 0 ? null : round2((double) totalOrders / customers),
                topCustomers);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
