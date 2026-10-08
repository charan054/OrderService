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

// Service key or an OWNER admin (see AdminPolicy). Newest first; optionally only one actor (an admin's username or "service-key").
@RestController
@RequestMapping("/audit")
public class AuditLogController {
    static final int MAX_LIMIT = 500;

    @Autowired
    private AuditLogRepository repository;

    @GetMapping("/recent")
    public List<AuditLogEntry> recent(@RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(required = false) String pathContains,
                                      @RequestParam(required = false) String actor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ProductException("Limit must be between 1 and " + MAX_LIMIT);
        }
        PageRequest page = PageRequest.of(0, limit);
        boolean byPath = pathContains != null && !pathContains.isBlank();
        boolean byActor = actor != null && !actor.isBlank();
        if (byActor && byPath) {
            return repository.findByActorIgnoreCaseAndPathContainingIgnoreCaseOrderByIdDesc(actor.trim(), pathContains.trim(), page);
        }
        if (byActor) {
            return repository.findByActorIgnoreCaseOrderByIdDesc(actor.trim(), page);
        }
        if (byPath) {
            return repository.findByPathContainingIgnoreCaseOrderByIdDesc(pathContains.trim(), page);
        }
        return repository.findAllByOrderByIdDesc(page);
    }
}
