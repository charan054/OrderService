package com.example.orderservice.controller;

import com.example.orderservice.entity.WebhookDelivery;
import com.example.orderservice.entity.WebhookEndpoint;
import com.example.orderservice.service.WebhookService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Owner-only (see AdminPolicy). A webhook is told about every order status change; the secret that signs its
// requests is returned once, by POST /webhooks.
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    public record RegisterRequest(String url) {
    }

    public record ActiveRequest(boolean active) {
    }

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping
    public WebhookService.Registered register(@RequestBody RegisterRequest request) {
        return service.register(request == null ? null : request.url());
    }

    @GetMapping
    public List<WebhookEndpoint> list() {
        return service.list();
    }

    @PutMapping("/{id}/active")
    public WebhookEndpoint setActive(@PathVariable long id, @RequestBody ActiveRequest request) {
        return service.setActive(id, request.active());
    }

    @GetMapping("/{id}/deliveries")
    public List<WebhookDelivery> deliveries(@PathVariable long id) {
        return service.recentDeliveries(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
