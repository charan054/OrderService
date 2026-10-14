package com.example.orderservice.controller;

import com.example.orderservice.dto.SharedOrderStatus;
import com.example.orderservice.entity.OrderShareLink;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.OrderShareService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// GET /track/{token} is public (the token is the credential). Making and revoking a link belong to the order's own
// signed-in customer (or the service key).
@RestController
public class OrderShareController {
    private final OrderShareService service;

    public OrderShareController(OrderShareService service) {
        this.service = service;
    }

    @PostMapping("/cart/{orderId}/share")
    public Map<String, Object> share(@PathVariable long orderId, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        OrderShareLink link = service.getOrCreate(orderId, phno);
        return Map.of("token", link.getToken(), "expiresAt", link.getExpiresAt().toString());
    }

    @DeleteMapping("/cart/{orderId}/share")
    public Map<String, Integer> revoke(@PathVariable long orderId, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return Map.of("revoked", service.revoke(orderId, phno));
    }

    @GetMapping("/track/{token}")
    public SharedOrderStatus view(@PathVariable String token) {
        return service.view(token);
    }
}
