package com.example.orderservice.controller;

import com.example.orderservice.entity.Faq;
import com.example.orderservice.service.FaqService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /faq is public (the storefront Help tab). Adding, editing and deleting fall under the default service-only
// rule, and the admin role policy (MANAGER or above) because they are not listed as SUPPORT-level.
@RestController
@RequestMapping("/faq")
public class FaqController {
    private final FaqService service;

    public FaqController(FaqService service) {
        this.service = service;
    }

    @GetMapping
    public List<Faq> list() {
        return service.list();
    }

    // With an id in the body this edits that entry; without one it adds a new entry.
    @PostMapping("/save")
    public Faq save(@RequestBody Faq faq) {
        return service.save(faq);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
