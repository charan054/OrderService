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
}
