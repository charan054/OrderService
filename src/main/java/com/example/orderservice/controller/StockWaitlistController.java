package com.example.orderservice.controller;

import com.example.orderservice.dto.WaitlistStatus;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// All three endpoints here are public - a customer joining/leaving/checking their OWN "notify me" waitlist by
// their OWN phone number, no admin dashboard use case exists for this (unlike Wishlist, which also has an
// X-Service-Key-gated /add and /remove for the internal dashboard). Same self-service trust level as
// /wishlist/self/add and /cart/byphno (see SecurityConfig).
@RestController
@RequestMapping("/waitlist")
public class StockWaitlistController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/self/add")
    public StockWaitlist addToWaitlist(@RequestParam long phno, @RequestParam int productId) {
        return orderService.addToWaitlist(phno, productId);
    }

    @GetMapping("/byphno")
    public List<WaitlistStatus> getWaitlist(@RequestParam long phno) {
        return orderService.getWaitlist(phno);
    }

    @DeleteMapping("/self/remove")
    public void removeFromWaitlist(@RequestParam long phno, @RequestParam int productId) {
        orderService.removeFromWaitlist(phno, productId);
    }
}
