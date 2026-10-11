package com.example.orderservice.service;

import com.example.orderservice.dto.InventoryValue;
import com.example.orderservice.dto.InventoryValue.CategoryValue;
import com.example.orderservice.dto.Product;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Stock on hand x selling price, from the live catalog. Nothing is stored. */
@Service
public class InventoryValueService {
    private final OrderService orderService;

    public InventoryValueService(OrderService orderService) {
        this.orderService = orderService;
    }

    public InventoryValue report() {
        return summarise(orderService.getProducts());
    }

    static InventoryValue summarise(List<Product> products) {
        // Case-insensitive grouping: the dev catalog has both "Groceries" and "grocery"-style spellings.
        Map<String, double[]> byCategory = new TreeMap<>();
        Map<String, String> display = new TreeMap<>();
        long units = 0;
        double total = 0;
        for (Product p : products) {
            int stock = Math.max(0, p.getProductStock());
            double value = stock * p.getProductPrice();
            String raw = p.getProductCategory() == null || p.getProductCategory().isBlank() ? "Uncategorised" : p.getProductCategory().trim();
            String key = raw.toLowerCase(Locale.ROOT);
            display.putIfAbsent(key, raw);
            double[] acc = byCategory.computeIfAbsent(key, k -> new double[3]);
            acc[0] += 1;
            acc[1] += stock;
            acc[2] += value;
            units += stock;
            total += value;
        }
        List<CategoryValue> categories = byCategory.entrySet().stream()
                .map(e -> new CategoryValue(display.get(e.getKey()), (int) e.getValue()[0], (long) e.getValue()[1], round(e.getValue()[2])))
                .sorted(Comparator.comparingDouble(CategoryValue::value).reversed())
                .toList();
        return new InventoryValue(products.size(), units, round(total), categories);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
