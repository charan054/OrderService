package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A customer's booked pickup of some units of ONE item of a delivered order (returns.pickup). Staff walk it
// AWAITING_PICKUP -> PICKED_UP -> RECEIVED; the refund is paid on RECEIVED (see ReturnPickupService).
@Data
@Entity
@Table(name = "item_return_pickup")
public class ItemReturnPickup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long orderId;
    private long customerPhno;
    private int productId;
    private int quantity;
    @Column(length = 300)
    private String reason;
    @Column(length = 20)
    private String refundTo;
    // AWAITING_PICKUP, PICKED_UP or RECEIVED - a plain string so a new step is not a schema change.
    @Column(length = 20)
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
