package com.example.orderservice.controller;

import com.example.orderservice.dto.SubscriptionRequest;
import com.example.orderservice.entity.Subscription;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.SubscriptionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Customer endpoints need the customer's own session (or the service key) for the phno they act on - see SecurityConfig;
// terms is public (it is just the offer) and run / all are service-only.
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {
    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/terms")
    public SubscriptionService.Terms terms() {
        return service.terms();
    }

    @PostMapping
    public Subscription create(@RequestBody SubscriptionRequest request) {
        CustomerAccess.requireSelfOrService(request.phno());
        return service.create(request.phno(), request.customerName(), request.productId(), request.quantity(),
                request.intervalDays(), request.shippingAddressId(), request.startNow());
    }

    @GetMapping("/mine")
    public List<Subscription> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.mine(phno);
    }

    @PutMapping("/{id}/pause")
    public Subscription pause(@PathVariable long id, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.pause(id, phno);
    }

    @PutMapping("/{id}/resume")
    public Subscription resume(@PathVariable long id, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.resume(id, phno);
    }

    @PutMapping("/{id}/skip")
    public Subscription skip(@PathVariable long id, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.skipNext(id, phno);
    }

    @DeleteMapping("/{id}")
    public Subscription cancel(@PathVariable long id, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.cancel(id, phno);
    }

    // ---- service only ----

    @GetMapping("/all")
    public List<Subscription> all() {
        return service.all();
    }

    // Places every order that is due now (the scheduler does the same on a timer when subscription.enabled=true).
    @PostMapping("/run")
    public SubscriptionService.RunResult run() {
        return service.runDue();
    }
}
