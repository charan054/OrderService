package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "stock_waitlist", uniqueConstraints = @UniqueConstraint(columnNames = {"customerPhno", "productId"}))
public class StockWaitlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private int productId;
    // When the back-in-stock email for the current restock was sent (see StockAlertService). Cleared again once the
    // product is seen out of stock, so the NEXT restock emails the customer once more. Null = not notified yet.
    private Instant notifiedAt;
    // One unit is held for this customer until this moment, from the restock email on (see StockAlertService); other
    // customers' checkouts can't take it. Null = no hold. A hold is released by expiring, by the customer ordering,
    // or by the product selling out.
    private Instant heldUntil;
}
