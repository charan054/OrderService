package com.example.orderservice.controller;

import com.example.orderservice.dto.CustomerInsights;
import com.example.orderservice.service.CustomerInsightsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Service-key only (SecurityConfig default), like the other admin analytics.
@RestController
public class CustomerInsightsController {
    @Autowired
    private CustomerInsightsService service;

    @GetMapping("/cart/analytics/customers")
    public CustomerInsights insights(@RequestParam(defaultValue = "10") int top) {
        return service.insights(top);
    }
}
