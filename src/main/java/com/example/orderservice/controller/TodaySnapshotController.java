package com.example.orderservice.controller;

import com.example.orderservice.dto.TodaySnapshot;
import com.example.orderservice.service.TodaySnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Service-key only (SecurityConfig default), like the other admin analytics.
@RestController
public class TodaySnapshotController {
    @Autowired
    private TodaySnapshotService service;

    @GetMapping("/cart/analytics/today")
    public TodaySnapshot today(@RequestParam(required = false) String zone,
                               @RequestParam(defaultValue = "24") int staleHours) {
        return service.snapshot(zone, staleHours);
    }
}
