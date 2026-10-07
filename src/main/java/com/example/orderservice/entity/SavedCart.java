package com.example.orderservice.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// A customer's not-yet-ordered shopping cart, kept on the server so it follows them across browsers and devices.
// Only product ids and quantities are stored - names, prices and stock are looked up live when the cart is
// restored, so a saved cart can never hold a stale price. One row per phone number; replaced wholesale on save.
@Data
@Entity
@Table(name = "saved_cart")
public class SavedCart {
    @Id
    private long phno;
    private Instant updatedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "saved_cart_line", joinColumns = @JoinColumn(name = "phno"))
    private List<Line> lines = new ArrayList<>();

    @Data
    @Embeddable
    public static class Line {
        private int productId;
        private int quantity;

        public Line() {
        }

        public Line(int productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }
    }
}
