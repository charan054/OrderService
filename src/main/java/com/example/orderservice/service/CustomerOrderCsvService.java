package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A customer's own order history as a spreadsheet: one row per item line, newest order first. Order-level amounts
 * (delivery fee, discount, total) repeat on every line of that order so the file can be filtered or summed per order
 * id. Product names come from the catalog; one that can no longer be looked up shows as "Product #id".
 */
@Service
public class CustomerOrderCsvService {
    static final String HEADER = "orderId,placedAt,status,paymentMethod,paid,product,quantity,unitPrice,lineTotal,"
            + "couponCode,discountAmount,deliveryFee,orderTotal,invoiceNumber\r\n";

    private final CartRepository carts;
    private final TrackingEventRepository tracking;
    private final ProductClient productClient;

    public CustomerOrderCsvService(CartRepository carts, TrackingEventRepository tracking, ProductClient productClient) {
        this.carts = carts;
        this.tracking = tracking;
        this.productClient = productClient;
    }

    public String export(long phno) {
        String digits = String.valueOf(phno);
        if (digits.length() != 10 || !digits.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
        List<Cart> orders = carts.findBycustomerPhno(phno).stream()
                .sorted(Comparator.comparing(Cart::getOrderId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<Integer, String> names = new HashMap<>();
        StringBuilder csv = new StringBuilder(HEADER);
        for (Cart order : orders) {
            Instant placedAt = tracking.findByOrderIdOrderByTimestampAsc(order.getOrderId()).stream()
                    .map(TrackingEvent::getTimestamp).findFirst().orElse(null);
            List<OrderItem> items = order.getOrderItems() == null ? List.of() : order.getOrderItems();
            for (OrderItem item : items) {
                double unit = item.getUnitPrice() == null ? 0 : item.getUnitPrice();
                csv.append(order.getOrderId()).append(',')
                        .append(placedAt == null ? "" : placedAt).append(',')
                        .append(order.getStatus()).append(',')
                        .append(order.getPaymentMethod()).append(',')
                        .append(order.isPaid()).append(',')
                        .append(OrderService.csvCell(nameOf(item.getProductId(), names))).append(',')
                        .append(item.getProductQuantity()).append(',')
                        .append(item.getUnitPrice() == null ? "" : unit).append(',')
                        .append(item.getUnitPrice() == null ? "" : unit * item.getProductQuantity()).append(',')
                        .append(OrderService.csvCell(order.getCouponCode())).append(',')
                        .append(order.getDiscountAmount()).append(',')
                        .append(order.getDeliveryFee()).append(',')
                        .append(order.getTotalPrice()).append(',')
                        .append(OrderService.csvCell(order.getInvoiceNumber())).append("\r\n");
            }
        }
        return csv.toString();
    }

    private String nameOf(int productId, Map<Integer, String> cache) {
        return cache.computeIfAbsent(productId, id -> {
            try {
                Product p = productClient.getProductById(id);
                if (p != null && p.getProductName() != null && !p.getProductName().isBlank()) {
                    return p.getProductName();
                }
            } catch (RuntimeException e) {
                // fall through to the placeholder
            }
            return "Product #" + id;
        });
    }
}
