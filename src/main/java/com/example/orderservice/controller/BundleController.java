package com.example.orderservice.controller;

import com.example.orderservice.dto.BundleView;
import com.example.orderservice.entity.Bundle;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.service.BundleService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// GET /bundles is public (the storefront shelf). Creating and retiring are service-key only (SecurityConfig default).
@RestController
@RequestMapping("/bundles")
public class BundleController {
    private final BundleService service;

    public BundleController(BundleService service) {
        this.service = service;
    }

    @GetMapping
    public List<BundleView> list() {
        return service.listActive();
    }

    // items is "productId:quantity,productId:quantity", e.g. items=3:2,24:1
    @PostMapping
    public Bundle create(@RequestParam String name, @RequestParam double price, @RequestParam String items) {
        Map<Integer, Integer> quantities = new LinkedHashMap<>();
        try {
            for (String part : items.split(",")) {
                String[] pair = part.trim().split(":");
                if (pair.length != 2 || quantities.put(Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim())) != null) {
                    throw new ProductException("items must look like 3:2,24:1 with each product once");
                }
            }
        } catch (NumberFormatException e) {
            throw new ProductException("items must look like 3:2,24:1 with each product once");
        }
        return service.create(name, price, quantities);
    }

    @DeleteMapping("/{id}")
    public void retire(@PathVariable long id) {
        service.retire(id);
    }
}
