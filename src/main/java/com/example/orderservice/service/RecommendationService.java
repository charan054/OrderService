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
 * "Picked for you": products to suggest to one customer, from real order data (same in-memory pass over the orders
 * that frequently-bought-together uses, at this system's scale).
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

        Set<Integer> owned = new HashSet<>();
        List<List<Integer>> keptOrders = new ArrayList<>();
        Map<Integer, Integer> unitsSold = new HashMap<>();
        for (Cart order : orders.findAll()) {
            if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.PENDING_PAYMENT
                    || order.getOrderItems() == null) {
                continue;
            }
            List<Integer> ids = order.getOrderItems().stream().map(OrderItem::getProductId).distinct().toList();
            keptOrders.add(ids);
            for (OrderItem item : order.getOrderItems()) {
                unitsSold.merge(item.getProductId(), Math.max(1, item.getProductQuantity()), Integer::sum);
            }
            if (order.getCustomerPhno() == phno) {
                owned.addAll(ids);
            }
        }

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

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
