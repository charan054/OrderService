package com.example.orderservice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// A URL that is told about every order status change. The secret signs each request so the receiver can tell it
// came from here; it is shown once when the endpoint is registered and never in a listing.
@Data
@Entity
@Table(name = "webhook_endpoint")
public class WebhookEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String url;
    @JsonIgnore
    private String secret;
    private boolean active = true;
    // Deliveries in a row that gave up; reaching WebhookService.DISABLE_AFTER switches the endpoint off.
    private int failedStreak;
    private Instant createdAt;
}
