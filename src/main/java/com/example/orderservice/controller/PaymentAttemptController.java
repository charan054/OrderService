package com.example.orderservice.controller;

import com.example.orderservice.entity.PaymentAttempt;
import com.example.orderservice.service.PaymentAttemptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Staff only (SecurityConfig's default rule): charges that may have moved money with no order to show for it.
@RestController
@RequestMapping("/cart/payment-attempts")
public class PaymentAttemptController {
    private final PaymentAttemptService service;

    public PaymentAttemptController(PaymentAttemptService service) {
        this.service = service;
    }

    @GetMapping("/attention")
    public List<PaymentAttempt> attention() {
        return service.needingAttention();
    }

    // Once the money was refunded by hand (or confirmed never taken), close it with a note.
    @PostMapping("/{id}/resolve")
    public PaymentAttempt resolve(@PathVariable long id, @RequestParam String note) {
        return service.resolve(id, note);
    }
}
