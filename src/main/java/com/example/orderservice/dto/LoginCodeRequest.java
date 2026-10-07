package com.example.orderservice.dto;

// email is only used while this phone has no verified email yet; afterwards the code always goes to the bound one.
public record LoginCodeRequest(long phno, String email) {
}
