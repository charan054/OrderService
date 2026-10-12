package com.example.orderservice.dto;

// One delivery window as the storefront needs it: its key, a display label, and whether it has reached the
// open-order cap (delivery.slot-capacity) and so cannot take another order right now.
public record SlotAvailability(String slot, String label, boolean full) {
}
