package com.example.orderservice;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "frequently bought together" counting runs in the database; this runs the real query on H2.
@SpringBootTest
@ActiveProfiles("test")
class CoPurchasedProductsQueryTest {

    @Autowired
    private CartRepository carts;

    @BeforeEach
    void clean() {
        carts.deleteAll();
    }

    private void order(OrderStatus status, int... productIds) {
        Cart cart = new Cart();
        cart.setCustomerName("Buyer");
        cart.setCustomerPhno(9876543210L);
        cart.setStatus(status);
        List<OrderItem> items = new ArrayList<>();
        for (int id : productIds) {
            OrderItem item = new OrderItem();
            item.setProductId(id);
            item.setProductQuantity(1);
            items.add(item);
        }
        cart.setOrderItems(items);
        carts.save(cart);
    }

    @Test
    void ranksOtherProductsByHowManyOrdersTheyShareAndSkipsCancelled() {
        order(OrderStatus.PLACED, 1, 2);
        order(OrderStatus.DELIVERED, 1, 2, 3);
        order(OrderStatus.RETURNED, 1, 3);
        order(OrderStatus.CANCELLED, 1, 4);
        order(null, 1, 5);          // very old rows have no status and still count
        order(OrderStatus.PLACED, 2, 3); // no product 1: ignored

        List<Object[]> rows = carts.findCoPurchasedProducts(1, PageRequest.of(0, 10));

        assertEquals(List.of(2, 3, 5), rows.stream().map(r -> ((Number) r[0]).intValue()).toList());
        assertEquals(List.of(2, 2, 1), rows.stream().map(r -> ((Number) r[1]).intValue()).toList());
    }

    @Test
    void pageSizeLimitsTheRows() {
        order(OrderStatus.PLACED, 1, 2, 3, 4);

        assertEquals(2, carts.findCoPurchasedProducts(1, PageRequest.of(0, 2)).size());
        assertTrue(carts.findCoPurchasedProducts(99, PageRequest.of(0, 5)).isEmpty());
    }
}
