package com.example.orderservice.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;
@Data
@Table(name="cart")
@Entity
@JsonPropertyOrder({
        "orderId",
        "customerName",
        "customerPhno",
        "orderItems",
        "totalPrice",
        "status"
})
public class Cart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;
    private String customerName;
    private long customerPhno;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name="order_items_orderId")
    private List<OrderItem> orderItems;
    private double totalPrice;
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PLACED;
    // The PhonepayService transaction that paid for this order (see OrderService.order()); needed to refund it
    // on cancellation. Null for orders placed before this field existed.
    private Long paymentTransactionId;

}
