package com.example.orderservice.dto;

// What the dashboard needs to know before anyone has signed in: whether the shared service key is still accepted
// from a browser (see NamedLoginPolicy).
public record AdminConfig(boolean namedLoginRequired) {
}
