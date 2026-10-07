package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="orderItem")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private int productId;
    private int productQuantity;
    // What one unit cost when the order was placed (null for orders placed before this was recorded). Per-item
    // cancel/return needs it to work out an item's share of what was actually paid.
    private Double unitPrice;
    // Units cancelled (before shipping) or returned (after delivery) one item at a time - see
    // OrderService.cancelItem()/returnItem(). productQuantity itself always stays what was originally ordered.
    private int cancelledQuantity;
    private int returnedQuantity;
    @Column(length = 255)
    private String returnReason;

    // Still with the customer: neither cancelled nor returned. Serialized too, so clients don't recompute it.
    public int getOutstandingQuantity() {
        return productQuantity - cancelledQuantity - returnedQuantity;
    }
}
