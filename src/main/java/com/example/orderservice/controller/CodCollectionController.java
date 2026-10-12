package com.example.orderservice.controller;

import com.example.orderservice.dto.CodCollectionReport;
import com.example.orderservice.service.CodCollectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Staff only (SecurityConfig's default rule, like /cart/analytics/today).
@RestController
public class CodCollectionController {
    @Autowired
    private CodCollectionService service;

    @GetMapping("/cart/analytics/cod")
    public CodCollectionReport cod(@RequestParam(required = false) String zone) {
        return service.report(zone);
    }
}
