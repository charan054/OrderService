package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

// One row per status an order has ever moved through, in the order it happened - the timeline that GET
// /cart/{orderId}/tracking renders. Written once per transition (order()/ship()/deliver()/cancel()) and never
// updated or deleted afterward, so this is an append-only audit log, not a mutable "current status" field
// (that's still Cart.status).
@Data
@Table(name = "tracking_event")
@Entity
public class TrackingEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private OrderStatus status;
    private Instant timestamp;
}
