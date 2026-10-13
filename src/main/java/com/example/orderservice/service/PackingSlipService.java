package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PackingSlips;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Packing slips and a combined pick list for orders that are about to go out (placed, or already shipped for a reprint).
 * Only units still with the customer are listed - cancelled or returned ones are not packed. Cash still to be collected
 * is shown on the slip so the courier knows what to ask for.
 */
@Service
public class PackingSlipService {
    static final int MAX_ORDERS = 200;

    private final CartRepository orders;
    private final ShippingAddressRepository addresses;
    private final ProductClient productClient;

    public PackingSlipService(CartRepository orders, ShippingAddressRepository addresses, ProductClient productClient) {
        this.orders = orders;
        this.addresses = addresses;
        this.productClient = productClient;
    }

    /** Slips for the given orders, or for every order in the status (PLACED when null) - oldest first, at most MAX_ORDERS. */
    public PackingSlips build(OrderStatus status, List<Long> orderIds) {
        List<Cart> batch;
        if (orderIds != null && !orderIds.isEmpty()) {
            if (orderIds.size() > MAX_ORDERS) {
                throw new ProductException("At most " + MAX_ORDERS + " orders at a time");
            }
            batch = orders.findAllById(orderIds).stream()
                    .filter(o -> o.getStatus() == OrderStatus.PLACED || o.getStatus() == OrderStatus.SHIPPED).collect(Collectors.toList());
        } else {
            OrderStatus wanted = status == null ? OrderStatus.PLACED : status;
            if (wanted != OrderStatus.PLACED && wanted != OrderStatus.SHIPPED) {
                throw new ProductException("Packing slips are for placed or shipped orders");
            }
            batch = orders.findByStatus(wanted);
        }
        batch.sort(Comparator.comparingLong(Cart::getOrderId));
        if (batch.size() > MAX_ORDERS) {
            batch = batch.subList(0, MAX_ORDERS);
        }

        Map<Integer, String> names = new HashMap<>();
        List<PackingSlips.Slip> slips = new ArrayList<>();
        Map<Integer, Integer> unitsByProduct = new HashMap<>();
        Map<Integer, Set<Long>> ordersByProduct = new HashMap<>();
        for (Cart order : batch) {
            List<PackingSlips.Line> lines = new ArrayList<>();
            for (OrderItem item : order.getOrderItems() == null ? List.<OrderItem>of() : order.getOrderItems()) {
                int qty = item.getOutstandingQuantity();
                if (qty <= 0) {
                    continue;
                }
                lines.add(new PackingSlips.Line(item.getProductId(), names.computeIfAbsent(item.getProductId(), this::nameOf), qty));
                unitsByProduct.merge(item.getProductId(), qty, Integer::sum);
                ordersByProduct.computeIfAbsent(item.getProductId(), k -> new HashSet<>()).add(order.getOrderId());
            }
            if (lines.isEmpty()) {
                continue;
            }
            boolean cashDue = order.getPaymentMethod() == com.example.orderservice.entity.PaymentMethod.CASH && !order.isPaid();
            double toCollect = cashDue ? Math.max(0, Math.round((order.getTotalPrice() - order.getRefundedAmount()) * 100.0) / 100.0) : 0;
            slips.add(new PackingSlips.Slip(order.getOrderId(), order.getCustomerName(), order.getCustomerPhno(), addressOf(order),
                    order.getDeliverySlot(), order.getDeliveryNote(), order.isGiftWrap(), order.getGiftMessage(),
                    String.valueOf(order.getPaymentMethod()), toCollect, lines));
        }
        List<PackingSlips.PickLine> pick = unitsByProduct.entrySet().stream()
                .map(e -> new PackingSlips.PickLine(e.getKey(), names.get(e.getKey()), e.getValue(), ordersByProduct.get(e.getKey()).size()))
                .sorted(Comparator.comparing(PackingSlips.PickLine::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        return new PackingSlips(slips, pick);
    }

    private String addressOf(Cart order) {
        if (order.getShippingAddressId() == null) {
            return null;
        }
        ShippingAddress a = addresses.findById(order.getShippingAddressId()).orElse(null);
        if (a == null) {
            return null;
        }
        return Stream.of(a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPincode())
                .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(", "));
    }

    private String nameOf(int productId) {
        try {
            Product p = productClient.getProductById(productId);
            return p == null || p.getProductName() == null ? "Product #" + productId : p.getProductName();
        } catch (FeignException e) {
            return "Product #" + productId;
        }
    }
}
