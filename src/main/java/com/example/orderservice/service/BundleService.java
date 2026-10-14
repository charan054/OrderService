package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.BundleView;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Bundle;
import com.example.orderservice.entity.BundleItem;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.BundleRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-price bundles. A bundle does not change what an order contains - the parts are ordinary order lines - it only
 * takes money off at checkout: every complete set found in the order is discounted to the bundle price (see
 * {@link #discountFor}). Because it is an ordinary discount, GST, refunds and credit notes already share it out
 * across the lines.
 */
@Service
public class BundleService {
    static final int MAX_ITEMS = 10;
    static final int MAX_QUANTITY = 20;

    private final BundleRepository bundles;
    private final ProductClient productClient;
    private final Clock clock;

    public BundleService(BundleRepository bundles, ProductClient productClient, Clock clock) {
        this.bundles = bundles;
        this.productClient = productClient;
        this.clock = clock;
    }

    public Bundle create(String name, double bundlePrice, Map<Integer, Integer> quantities) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new ProductException("A bundle needs a name of up to 120 characters");
        }
        if (Double.isNaN(bundlePrice) || Double.isInfinite(bundlePrice) || bundlePrice <= 0) {
            throw new ProductException("Bundle price must be greater than 0");
        }
        if (quantities == null || quantities.size() < 2 || quantities.size() > MAX_ITEMS) {
            throw new ProductException("A bundle needs between 2 and " + MAX_ITEMS + " different products");
        }
        double regularTotal = 0;
        Bundle bundle = new Bundle();
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            int qty = entry.getValue() == null ? 0 : entry.getValue();
            if (qty < 1 || qty > MAX_QUANTITY) {
                throw new ProductException("Each quantity must be between 1 and " + MAX_QUANTITY);
            }
            Product product = lookup(entry.getKey());
            regularTotal += qty * product.getProductPrice();
            BundleItem item = new BundleItem();
            item.setProductId(entry.getKey());
            item.setQuantity(qty);
            bundle.getItems().add(item);
        }
        if (bundlePrice >= regularTotal) {
            throw new ProductException("Bundle price must be lower than buying the parts separately (" + regularTotal + ")");
        }
        bundle.setName(trimmed);
        bundle.setBundlePrice(bundlePrice);
        bundle.setCreatedAt(Instant.now(clock));
        return bundles.save(bundle);
    }

    public void retire(long id) {
        Bundle bundle = bundles.findById(id).orElseThrow(() -> new ProductException("Bundle not found"));
        bundle.setActive(false);
        bundles.save(bundle);
    }

    /** Active bundles priced from the live catalog; one whose products cannot be loaded is left out. */
    public List<BundleView> listActive() {
        List<BundleView> views = new ArrayList<>();
        for (Bundle bundle : bundles.findByActiveTrueOrderByIdAsc()) {
            try {
                views.add(view(bundle));
            } catch (RuntimeException e) {
                // A bundle with a deleted product cannot be bought; hide it rather than fail the whole list.
            }
        }
        return views;
    }

    private BundleView view(Bundle bundle) {
        List<BundleView.Line> lines = new ArrayList<>();
        double regularTotal = 0;
        boolean available = true;
        for (BundleItem item : bundle.getItems()) {
            Product p = lookup(item.getProductId());
            regularTotal += item.getQuantity() * p.getProductPrice();
            available &= p.getProductStock() >= item.getQuantity();
            lines.add(new BundleView.Line(item.getProductId(), p.getProductName(), item.getQuantity(), p.getProductPrice(), p.getProductStock()));
        }
        double savings = Math.max(0, Math.round((regularTotal - bundle.getBundlePrice()) * 100.0) / 100.0);
        return new BundleView(bundle.getId(), bundle.getName(), bundle.getBundlePrice(), regularTotal, savings, available, lines);
    }

    private Product lookup(int productId) {
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product " + productId + " not found");
        }
        if (product == null) {
            throw new ProductException("Product " + productId + " not found");
        }
        return product;
    }

    /**
     * What the complete bundle sets in an order save. quantities is product id -> units ordered, unitPrices is product id
     * -> price charged per unit. Bundles are tried biggest saving first and each unit is used by at most one set, so two
     * bundles sharing a product never discount the same unit twice. A bundle that would not save anything (a sale already
     * made the parts cheaper) is skipped.
     */
    public static double discountFor(List<Bundle> activeBundles, Map<Integer, Integer> quantities, Map<Integer, Double> unitPrices) {
        record Candidate(Bundle bundle, double savingPerSet) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Bundle bundle : activeBundles) {
            double parts = 0;
            boolean priced = !bundle.getItems().isEmpty();
            for (BundleItem item : bundle.getItems()) {
                Double price = unitPrices.get(item.getProductId());
                if (price == null) {
                    priced = false;
                    break;
                }
                parts += item.getQuantity() * price;
            }
            double saving = parts - bundle.getBundlePrice();
            if (priced && saving > 0.004) {
                candidates.add(new Candidate(bundle, saving));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::savingPerSet).reversed());
        Map<Integer, Integer> left = new HashMap<>(quantities);
        double total = 0;
        for (Candidate c : candidates) {
            int sets = Integer.MAX_VALUE;
            for (BundleItem item : c.bundle().getItems()) {
                sets = Math.min(sets, left.getOrDefault(item.getProductId(), 0) / item.getQuantity());
            }
            if (sets <= 0 || sets == Integer.MAX_VALUE) {
                continue;
            }
            for (BundleItem item : c.bundle().getItems()) {
                left.merge(item.getProductId(), -sets * item.getQuantity(), Integer::sum);
            }
            total += sets * c.savingPerSet();
        }
        return Math.round(total * 100.0) / 100.0;
    }
}
