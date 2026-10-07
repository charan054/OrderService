package com.example.orderservice.dto;

// What one abandoned-cart run did: reminder emails sent, carts that qualified but whose owner has no verified email
// (nothing recorded - reminded once they have one, while the cart is still inside the window), carts skipped because
// none of their products exist any more, and sends that failed (retried on the next run).
public record AbandonedCartResult(int remindersSent, int cartsWithoutEmail, int cartsSkipped, int sendFailures) {
}
