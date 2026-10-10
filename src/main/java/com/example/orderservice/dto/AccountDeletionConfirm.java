package com.example.orderservice.dto;

// The emailed code, and whether the customer accepts losing any loyalty points / store credit they still have.
public record AccountDeletionConfirm(String code, boolean acknowledgeForfeit) {
}
