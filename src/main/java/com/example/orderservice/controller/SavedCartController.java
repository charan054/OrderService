package com.example.orderservice.controller;

import com.example.orderservice.entity.SavedCart;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.SavedCartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The signed-in customer's own saved cart (or the service key) - see SecurityConfig and CustomerAccess.
@RestController
@RequestMapping("/savedcart")
public class SavedCartController {
    @Autowired
    private SavedCartService service;
    @Autowired
    private com.example.orderservice.service.AbandonedCartService abandonedCartService;

    @GetMapping
    public SavedCart get(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.get(phno);
    }

    // Admin-only (service key by default): send the abandoned-cart reminder emails now instead of waiting for the
    // hourly job (which is off unless abandoned-cart.enabled=true). Each cart is reminded once per change.
    @PostMapping("/reminders/run")
    public com.example.orderservice.dto.AbandonedCartResult runReminders() {
        return abandonedCartService.run();
    }

    @PutMapping
    public SavedCart replace(@RequestParam long phno, @RequestBody List<SavedCart.Line> lines) {
        CustomerAccess.requireSelfOrService(phno);
        return service.replace(phno, lines);
    }
}
