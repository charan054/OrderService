package com.example.orderservice.controller;

import com.example.orderservice.dto.CustomerInsights;
import com.example.orderservice.service.CustomerExportService;
import com.example.orderservice.service.CustomerInsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Service-key only (SecurityConfig default), like the other admin analytics.
@RestController
public class CustomerInsightsController {
    @Autowired
    private CustomerInsightsService service;
    @Autowired
    private CustomerExportService exportService;

    @GetMapping("/cart/analytics/customers")
    public CustomerInsights insights(@RequestParam(defaultValue = "10") int top) {
        return service.insights(top);
    }

    // Customer list as CSV for mailing / follow-up; segment = all | new | repeat | dormant | active.
    @GetMapping(value = "/cart/analytics/customers/export", produces = "text/csv")
    public ResponseEntity<String> exportCustomers(@RequestParam(defaultValue = "all") String segment,
                                                  @RequestParam(defaultValue = "90") int dormantDays) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"customers.csv\"")
                .body(exportService.exportCsv(segment, dormantDays));
    }
}
