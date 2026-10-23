package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.Recommendation;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Picked for you": products to suggest to one customer, from real order data (one in-memory pass over the orders, shared
 * between customers for five minutes).
 * <ol>
 *   <li>BOUGHT_WITH - products that appeared in the same order as something this customer already bought, ranked by
 *       how many orders paired them.</li>
 *   <li>POPULAR - topped up with the best sellers overall (by units), which is also all a brand-new customer gets.</li>
 * </ol>
 * Things the customer already bought, sold-out products and products no longer in the catalog are never suggested.
 */
@Service
public class RecommendationService {
    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 20;
    static final String BOUGHT_WITH = "BOUGHT_WITH";
    static final String POPULAR = "POPULAR";

    private final CartRepository orders;
    private final ProductClient productClient;

    public RecommendationService(CartRepository orders, ProductClient productClient) {
        this.orders = orders;
        this.productClient = productClient;
    }

    public List<Recommendation> forCustomer(long phno, Integer limit) {
        validatePhno(phno);
        int wanted = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        Snapshot snapshot = snapshot();
        Set<Integer> owned = new HashSet<>();
        for (KeptOrder order : snapshot.orders()) {
            if (order.phno() == phno) {
                owned.addAll(order.productIds());
            }
        }
        List<List<Integer>> keptOrders = snapshot.orders().stream().map(KeptOrder::productIds).toList();
        Map<Integer, Integer> unitsSold = snapshot.unitsSold();

        // Orders that contain something the customer owns, scored per other product they contain.
        Map<Integer, Integer> pairedCount = new HashMap<>();
        for (List<Integer> ids : keptOrders) {
            if (ids.stream().noneMatch(owned::contains)) {
                continue;
            }
            for (int id : ids) {
                if (!owned.contains(id)) {
                    pairedCount.merge(id, 1, Integer::sum);
                }
            }
        }

        // Ranked candidate ids with the reason each was picked; best first, ties broken by lowest id for stability.
        Map<Integer, String> candidates = new LinkedHashMap<>();
        pairedCount.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> candidates.put(e.getKey(), BOUGHT_WITH));
        unitsSold.entrySet().stream()
                .filter(e -> !owned.contains(e.getKey()))
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> candidates.putIfAbsent(e.getKey(), POPULAR));

        // Look products up in rank order until enough are in stock; a failed lookup just skips that candidate.
        List<Recommendation> result = new ArrayList<>();
        for (Map.Entry<Integer, String> candidate : candidates.entrySet()) {
            if (result.size() >= wanted) {
                break;
            }
            Product product;
            try {
                product = productClient.getProductById(candidate.getKey());
            } catch (FeignException e) {
                continue;
            }
            if (product == null || product.getProductStock() <= 0) {
                continue;
            }
            result.add(new Recommendation(product.getProductId(), product.getProductName(), product.getProductCategory(),
                    product.getProductPrice(), product.getProductImageUrl(), candidate.getValue()));
        }
        return result;
    }

    // Reading every order is the expensive part and the same for every customer, so it is shared for a short while.
    static final long SNAPSHOT_TTL_MILLIS = 5 * 60 * 1000L;

    record KeptOrder(long phno, List<Integer> productIds) {
    }

    record Snapshot(List<KeptOrder> orders, Map<Integer, Integer> unitsSold, long takenAt) {
    }

    private volatile Snapshot cached;

    void invalidate() {
        cached = null;
    }

    private Snapshot snapshot() {
        Snapshot current = cached;
        long now = System.currentTimeMillis();
        if (current != null && now - current.takenAt() < SNAPSHOT_TTL_MILLIS) {
            return current;
        }
        List<KeptOrder> kept = new ArrayList<>();
        Map<Integer, Integer> unitsSold = new HashMap<>();
        for (Cart order : orders.findAll()) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.PENDING_PAYMENT
                    || order.getOrderItems() == null) {
                continue;
            }
            kept.add(new KeptOrder(order.getCustomerPhno(), order.getOrderItems().stream().map(OrderItem::getProductId).distinct().toList()));
            for (OrderItem item : order.getOrderItems()) {
                unitsSold.merge(item.getProductId(), Math.max(1, item.getProductQuantity()), Integer::sum);
            }
        }
        Snapshot fresh = new Snapshot(List.copyOf(kept), Map.copyOf(unitsSold), now);
        cached = fresh;
        return fresh;
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
