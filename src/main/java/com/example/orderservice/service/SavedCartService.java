package com.example.orderservice.service;

import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.SavedCartRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side copy of a customer's in-progress cart. Last write wins: the storefront replaces the whole cart on
 * every change, so two devices simply overwrite each other rather than merging.
 */
@Service
public class SavedCartService {
    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY_PER_LINE = 99;

    private final SavedCartRepository carts;
    private final Clock clock;

    public SavedCartService(SavedCartRepository carts, Clock clock) {
        this.carts = carts;
        this.clock = clock;
    }

    // Never null: a customer with nothing saved gets an empty cart.
    public SavedCart get(long phno) {
        validatePhno(phno);
        return carts.findById(phno).orElseGet(() -> {
            SavedCart empty = new SavedCart();
            empty.setPhno(phno);
            return empty;
        });
    }

    // Replaces the saved cart. Duplicate product ids are merged (quantities added, then capped); an empty list
    // deletes the saved cart so an emptied cart leaves nothing behind.
    public SavedCart replace(long phno, List<SavedCart.Line> lines) {
        validatePhno(phno);
        if (lines == null) {
            throw new ProductException("Cart lines are required");
        }
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (SavedCart.Line line : lines) {
            if (line == null || line.getProductId() <= 0 || line.getQuantity() < 1) {
                throw new ProductException("Each cart line needs a product id and a quantity of at least 1");
            }
            merged.merge(line.getProductId(), line.getQuantity(), Integer::sum);
        }
        if (merged.size() > MAX_LINES) {
            throw new ProductException("A cart can hold at most " + MAX_LINES + " different products");
        }
        if (merged.isEmpty()) {
            carts.deleteById(phno);
            return get(phno);
        }
        SavedCart cart = carts.findById(phno).orElseGet(SavedCart::new);
        cart.setPhno(phno);
        cart.setUpdatedAt(Instant.now(clock));
        cart.setReminderSentAt(null);
        cart.getLines().clear();
        merged.forEach((productId, quantity) ->
                cart.getLines().add(new SavedCart.Line(productId, Math.min(quantity, MAX_QUANTITY_PER_LINE))));
        return carts.save(cart);
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
