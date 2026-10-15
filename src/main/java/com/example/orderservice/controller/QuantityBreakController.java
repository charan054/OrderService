package com.example.orderservice.controller;

import com.example.orderservice.entity.QuantityBreak;
import com.example.orderservice.service.QuantityBreakService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /quantitybreaks is public (the storefront shows "buy 3+, save 10%"). Creating and retiring are service-key only.
@RestController
@RequestMapping("/quantitybreaks")
public class QuantityBreakController {
    private final QuantityBreakService service;

    public QuantityBreakController(QuantityBreakService service) {
        this.service = service;
    }

    @GetMapping
    public List<QuantityBreak> list() {
        return service.listActive();
    }

    @PostMapping
    public QuantityBreak create(@RequestParam int productId, @RequestParam int minQuantity, @RequestParam int percentOff) {
        return service.create(productId, minQuantity, percentOff);
    }

    @DeleteMapping("/{id}")
    public void retire(@PathVariable long id) {
        service.retire(id);
    }
}
