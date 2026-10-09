package com.example.orderservice.controller;

import com.example.orderservice.dto.HealthHistory;
import com.example.orderservice.dto.ServiceHealth;
import com.example.orderservice.service.HealthHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Admin only, like GET /cart/health: no public rule, so the service key (or a MANAGER/OWNER admin).
@RestController
@RequestMapping("/cart/health/history")
public class HealthHistoryController {
    private final HealthHistoryService history;

    public HealthHistoryController(HealthHistoryService history) {
        this.history = history;
    }

    // Uptime per service over the last N hours (default 24, max 168), with an hourly strip.
    @GetMapping
    public HealthHistory history(@RequestParam(required = false) Integer hours) {
        return history.history(hours);
    }

    // Probes the stack right now and stores the result (what the scheduler does on its timer).
    @PostMapping("/record")
    public List<ServiceHealth> record() {
        return history.record();
    }
}
