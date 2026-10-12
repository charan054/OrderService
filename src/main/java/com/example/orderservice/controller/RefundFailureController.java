package com.example.orderservice.controller;

import com.example.orderservice.entity.RefundFailure;
import com.example.orderservice.service.RefundFailureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Staff only (SecurityConfig's default rule, MANAGER and up for named admins - it is about money): refunds the system
// could not make on its own, until somebody settles them by hand.
@RestController
@RequestMapping("/refundfailures")
public class RefundFailureController {
    @Autowired
    private RefundFailureService service;

    @GetMapping
    public List<RefundFailure> open() {
        return service.open();
    }

    @PostMapping("/{id}/resolve")
    public RefundFailure resolve(@PathVariable long id, @RequestParam String note) {
        return service.resolve(id, note);
    }
}
