package com.example.orderservice.controller;

import com.example.orderservice.entity.AuditLogEntry;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Service-key only (SecurityConfig default rule). Newest first.
@RestController
@RequestMapping("/audit")
public class AuditLogController {
    static final int MAX_LIMIT = 500;

    @Autowired
    private AuditLogRepository repository;

    @GetMapping("/recent")
    public List<AuditLogEntry> recent(@RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(required = false) String pathContains) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ProductException("Limit must be between 1 and " + MAX_LIMIT);
        }
        PageRequest page = PageRequest.of(0, limit);
        if (pathContains == null || pathContains.isBlank()) {
            return repository.findAllByOrderByIdDesc(page);
        }
        return repository.findByPathContainingIgnoreCaseOrderByIdDesc(pathContains.trim(), page);
    }
}
