package com.example.orderservice.controller;

import com.example.orderservice.entity.OrderNote;
import com.example.orderservice.service.OrderNoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Service-key only (SecurityConfig's default rule) - these are staff-internal, a customer token never gets here.
@RestController
@RequestMapping("/ordernotes")
public class OrderNoteController {
    @Autowired
    private OrderNoteService service;

    @PostMapping
    public OrderNote add(@RequestParam long orderId, @RequestParam String note) {
        return service.add(orderId, note);
    }

    @GetMapping
    public List<OrderNote> list(@RequestParam long orderId) {
        return service.forOrder(orderId);
    }

    @DeleteMapping("/{noteId}")
    public void delete(@PathVariable long noteId) {
        service.delete(noteId);
    }
}
