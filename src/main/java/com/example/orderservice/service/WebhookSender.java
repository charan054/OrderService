package com.example.orderservice.service;

import java.io.IOException;
import java.util.Map;

/** Sends one webhook request and returns the HTTP status; a connection failure or timeout throws IOException. */
public interface WebhookSender {
    int post(String url, String body, Map<String, String> headers) throws IOException;
}
