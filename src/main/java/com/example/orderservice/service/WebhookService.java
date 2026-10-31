package com.example.orderservice.service;

import com.example.orderservice.entity.WebhookDelivery;
import com.example.orderservice.entity.WebhookEndpoint;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.WebhookDeliveryRepository;
import com.example.orderservice.repository.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells outside systems (a warehouse, an accounting tool) when an order changes status.
 *
 * A status change writes one WebhookDelivery per active endpoint straight away; {@link #dispatchDue()} - run by
 * WebhookScheduler - then sends them, so a slow or dead receiver never delays an order and a failure is retried
 * later (1 min, 5 min, 30 min, 2 h, 12 h) before giving up. Delivery is at-least-once: a receiver that sees the
 * same X-Webhook-Id twice should ignore the repeat. The body names the order and status only - no customer
 * details; the receiver reads the rest from the API.
 *
 * Each request carries X-Webhook-Timestamp and X-Webhook-Signature = "sha256=" + hex(HMAC-SHA256(secret,
 * timestamp + "." + body)), so a receiver can check it came from here and was not replayed.
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    static final int MAX_ENDPOINTS = 5;
    static final int MAX_ATTEMPTS = 6;
    static final int DISABLE_AFTER = 10;
    static final int BATCH = 50;
    static final Duration KEEP_FINISHED = Duration.ofDays(30);
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(12)};

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                          WebhookSender sender, Clock clock) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.sender = sender;
        this.clock = clock;
    }

    /** The only time the secret is visible: the caller must copy it from this result. */
    public record Registered(Long id, String url, String secret) {
    }

    public Registered register(String url) {
        String checked = WebhookUrls.validate(url);
        if (endpoints.count() >= MAX_ENDPOINTS) {
            throw new ProductException("At most " + MAX_ENDPOINTS + " webhooks can be registered");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(checked);
        endpoint.setSecret("whsec_" + HexFormat.of().formatHex(bytes));
        endpoint.setCreatedAt(clock.instant());
        WebhookEndpoint saved = endpoints.save(endpoint);
        return new Registered(saved.getId(), saved.getUrl(), saved.getSecret());
    }

    public List<WebhookEndpoint> list() {
        return endpoints.findAllByOrderByIdAsc();
    }

    public void delete(long id) {
        if (!endpoints.existsById(id)) {
            throw new OrderNotFoundException("Webhook not found");
        }
        deliveries.deleteByEndpointId(id);
        endpoints.deleteById(id);
    }

    /** Switches an endpoint back on (after it was auto-disabled) or off, and clears its failure count. */
    public WebhookEndpoint setActive(long id, boolean active) {
        WebhookEndpoint endpoint = endpoints.findById(id).orElseThrow(() -> new OrderNotFoundException("Webhook not found"));
        endpoint.setActive(active);
        endpoint.setFailedStreak(0);
        return endpoints.save(endpoint);
    }

    public List<WebhookDelivery> recentDeliveries(long id) {
        if (!endpoints.existsById(id)) {
            throw new OrderNotFoundException("Webhook not found");
        }
        return deliveries.findByEndpointIdOrderByIdDesc(id, PageRequest.of(0, 50));
    }

    // Anything that goes wrong is logged, never propagated: it must not turn a status change into an error.
    @EventListener
    public void onStatusChanged(OrderStatusChangedEvent event) {
        try {
            enqueue(event);
        } catch (RuntimeException e) {
            log.error("Could not queue webhooks for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }

    void enqueue(OrderStatusChangedEvent event) {
        Instant now = clock.instant();
        for (WebhookEndpoint endpoint : endpoints.findByActiveTrue()) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpointId(endpoint.getId());
            delivery.setOrderId(event.orderId());
            delivery.setStatus(event.status().name());
            delivery.setOccurredAt(event.at());
            delivery.setNextAttemptAt(now);
            deliveries.save(delivery);
        }
    }

    /** Sends every delivery that is due, then tidies old finished ones. Returns how many were attempted. */
    public int dispatchDue() {
        Instant now = clock.instant();
        List<WebhookDelivery> due = deliveries.findByStateAndNextAttemptAtLessThanEqualOrderByIdAsc(
                WebhookDelivery.PENDING, now, PageRequest.of(0, BATCH));
        for (WebhookDelivery delivery : due) {
            attempt(delivery);
        }
        deliveries.deleteFinishedBefore(now.minus(KEEP_FINISHED));
        return due.size();
    }

    private void attempt(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isActive()) {
            // switched off or removed since it was queued - nothing to send to, and nothing to retry
            finish(delivery, WebhookDelivery.FAILED, "Endpoint is switched off or was removed");
            return;
        }
        delivery.setAttempts(delivery.getAttempts() + 1);
        String error;
        try {
            WebhookUrls.validate(endpoint.getUrl());
            int code = sender.post(endpoint.getUrl(), body(delivery), headers(delivery, endpoint));
            error = code >= 200 && code < 300 ? null : "Receiver answered HTTP " + code;
        } catch (IOException | RuntimeException e) {
            error = trim(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (error == null) {
            delivery.setDeliveredAt(clock.instant());
            finish(delivery, WebhookDelivery.DELIVERED, null);
            if (endpoint.getFailedStreak() != 0) {
                endpoint.setFailedStreak(0);
                endpoints.save(endpoint);
            }
        } else if (delivery.getAttempts() >= MAX_ATTEMPTS) {
            finish(delivery, WebhookDelivery.FAILED, error);
            endpoint.setFailedStreak(endpoint.getFailedStreak() + 1);
            if (endpoint.getFailedStreak() >= DISABLE_AFTER) {
                endpoint.setActive(false);
                log.warn("Webhook {} switched off after {} deliveries in a row gave up", endpoint.getId(), DISABLE_AFTER);
            }
            endpoints.save(endpoint);
        } else {
            delivery.setLastError(error);
            delivery.setNextAttemptAt(clock.instant().plus(BACKOFF[delivery.getAttempts() - 1]));
            deliveries.save(delivery);
        }
    }

    private void finish(WebhookDelivery delivery, String state, String error) {
        delivery.setState(state);
        delivery.setLastError(error);
        delivery.setNextAttemptAt(null);
        deliveries.save(delivery);
    }

    static String body(WebhookDelivery delivery) {
        return "{\"id\":" + delivery.getId() + ",\"type\":\"order.status_changed\",\"orderId\":" + delivery.getOrderId()
                + ",\"status\":\"" + delivery.getStatus() + "\",\"occurredAt\":\"" + delivery.getOccurredAt() + "\"}";
    }

    private Map<String, String> headers(WebhookDelivery delivery, WebhookEndpoint endpoint) {
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Webhook-Id", String.valueOf(delivery.getId()));
        headers.put("X-Webhook-Timestamp", timestamp);
        headers.put("X-Webhook-Signature", sign(endpoint.getSecret(), timestamp, body(delivery)));
        return headers;
    }

    static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static String trim(String message) {
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
