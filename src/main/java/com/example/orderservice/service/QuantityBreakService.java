package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.QuantityBreak;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.QuantityBreakRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Quantity price breaks ("buy 3 or more, save 10%"). Like a bundle this is only a discount taken at checkout (see
 * {@link #discountFor}), so GST, refunds and credit notes already share it across the order lines. An order gets either
 * its bundle saving or its quantity-break saving, whichever is larger - never both on the same goods.
 */
@Service
public class QuantityBreakService {
    static final int MIN_QUANTITY = 2;
    static final int MAX_QUANTITY = 1000;
    static final int MAX_PERCENT = 90;

    private final QuantityBreakRepository breaks;
    private final ProductClient productClient;
    private final Clock clock;

    public QuantityBreakService(QuantityBreakRepository breaks, ProductClient productClient, Clock clock) {
        this.breaks = breaks;
        this.productClient = productClient;
        this.clock = clock;
    }

    public QuantityBreak create(int productId, int minQuantity, int percentOff) {
        if (minQuantity < MIN_QUANTITY || minQuantity > MAX_QUANTITY) {
            throw new ProductException("Minimum quantity must be between " + MIN_QUANTITY + " and " + MAX_QUANTITY);
        }
        if (percentOff < 1 || percentOff > MAX_PERCENT) {
            throw new ProductException("Discount must be between 1% and " + MAX_PERCENT + "%");
        }
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product not found");
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        if (breaks.existsByProductIdAndMinQuantityAndActiveTrue(productId, minQuantity)) {
            throw new ProductException("This product already has a break at " + minQuantity + " units - retire it first");
        }
        List<QuantityBreak> existing = breaks.findByActiveTrueOrderByProductIdAscMinQuantityAsc();
        for (QuantityBreak other : existing) {
            if (other.getProductId() != productId) continue;
            // A bigger order must never pay more per unit than a smaller one.
            if ((other.getMinQuantity() < minQuantity && other.getPercentOff() >= percentOff)
                    || (other.getMinQuantity() > minQuantity && other.getPercentOff() <= percentOff)) {
                throw new ProductException("A bigger quantity has to save more: this clashes with the " + other.getMinQuantity()
                        + "+ break at " + other.getPercentOff() + "%");
            }
        }
        QuantityBreak b = new QuantityBreak();
        b.setProductId(productId);
        b.setMinQuantity(minQuantity);
        b.setPercentOff(percentOff);
        b.setCreatedAt(Instant.now(clock));
        return breaks.save(b);
    }

    public void retire(long id) {
        QuantityBreak b = breaks.findById(id).orElseThrow(() -> new ProductException("Quantity break not found"));
        b.setActive(false);
        breaks.save(b);
    }

    public List<QuantityBreak> listActive() {
        return breaks.findByActiveTrueOrderByProductIdAscMinQuantityAsc();
    }

    /**
     * What the quantity breaks save on an order. quantities is product id -> units ordered, unitPrices is product id ->
     * price charged per unit. For each product the highest tier whose minimum the order reaches takes its percentage off
     * every unit of that product.
     */
    public static double discountFor(List<QuantityBreak> activeBreaks, Map<Integer, Integer> quantities, Map<Integer, Double> unitPrices) {
        double total = 0;
        for (Map.Entry<Integer, Integer> line : quantities.entrySet()) {
            Double price = unitPrices.get(line.getKey());
            if (price == null) continue;
            int best = 0;
            for (QuantityBreak b : activeBreaks) {
                if (b.getProductId() == line.getKey() && line.getValue() >= b.getMinQuantity() && b.getPercentOff() > best) {
                    best = b.getPercentOff();
                }
            }
            total += line.getValue() * price * best / 100.0;
        }
        return Math.round(total * 100.0) / 100.0;
    }
}
