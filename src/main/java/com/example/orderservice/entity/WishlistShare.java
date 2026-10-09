package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A customer's read-only wishlist share link. The random token is the whole credential - whoever holds the link
// can see the product list (never the phone number) until the customer revokes it. One link per customer.
@Data
@Entity
@Table(name = "wishlist_share")
public class WishlistShare {
    @Id
    private String token;
    private long customerPhno;
    private Instant createdAt;
    // How many times the public link has been opened (every load counts, including the owner's own and refreshes).
    private long viewCount;
}
