package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.CustomerPushService;
import com.example.orderservice.service.VapidKeys;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Browser notifications. The public key is public (the browser needs it to subscribe); subscribing and unsubscribing are the
// customer's own session (or the service key); a fresh key pair for the shop owner to put in .env is service-key only.
@RestController
@RequestMapping("/cart/push")
public class PushController {
    private final CustomerPushService push;

    public PushController(CustomerPushService push) {
        this.push = push;
    }

    public record SubscriptionRequest(long phno, String endpoint, String p256dh, String auth) {
    }

    @GetMapping("/key")
    public Map<String, Object> key() {
        return Map.of("enabled", push.isEnabled(), "publicKey", push.isEnabled() ? push.publicKey() : "");
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@RequestBody SubscriptionRequest request) {
        CustomerAccess.requireSelfOrService(request.phno());
        push.subscribe(request.phno(), request.endpoint(), request.p256dh(), request.auth());
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestParam long phno, @RequestParam String endpoint) {
        CustomerAccess.requireSelfOrService(phno);
        push.unsubscribe(phno, endpoint);
    }

    // Owner-only helper: a new VAPID pair to put in OrderService/.env as PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY.
    @GetMapping("/new-keys")
    public Map<String, String> newKeys() {
        VapidKeys.Pair pair = VapidKeys.generate();
        return Map.of("publicKey", pair.publicKey(), "privateKey", pair.privateKey(),
                "note", "Set PUSH_VAPID_PUBLIC_KEY and PUSH_VAPID_PRIVATE_KEY (and optionally PUSH_VAPID_SUBJECT=mailto:you@example.com) and restart.");
    }
}
