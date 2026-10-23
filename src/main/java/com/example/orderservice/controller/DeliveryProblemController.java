package com.example.orderservice.controller;

import com.example.orderservice.service.DeliveryProblemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Staff only (SecurityConfig's default rule, like /cart/analytics/today).
@RestController
public class DeliveryProblemController {
    @Autowired
    private DeliveryProblemService service;

    @GetMapping("/cart/analytics/delivery-problems")
    public DeliveryProblemService.Report problems(@RequestParam(required = false) Integer days) {
        return service.report(days);
    }
}
