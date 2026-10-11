package com.example.orderservice.controller;

import com.example.orderservice.dto.NeedsAttention;
import com.example.orderservice.service.NeedsAttentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Admin-only (service key or a named admin, SUPPORT and up - see AdminPolicy): counts of what is waiting on someone.
@RestController
public class NeedsAttentionController {
    private final NeedsAttentionService service;

    public NeedsAttentionController(NeedsAttentionService service) {
        this.service = service;
    }

    @GetMapping("/cart/attention")
    public NeedsAttention attention() {
        return service.get();
    }
}
