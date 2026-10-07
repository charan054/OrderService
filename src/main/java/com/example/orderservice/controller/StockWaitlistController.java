package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.WaitlistStatus;
import com.example.orderservice.entity.StockWaitlist;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// All three endpoints here are the storefront's own "notify me" waitlist: a signed-in customer (own phone number
// only, see CustomerAccess) or the service key. No separate admin pair exists, unlike Wishlist.
@RestController
@RequestMapping("/waitlist")
public class StockWaitlistController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/self/add")
    public StockWaitlist addToWaitlist(@RequestParam long phno, @RequestParam int productId) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.addToWaitlist(phno, productId);
    }

    @GetMapping("/byphno")
    public List<WaitlistStatus> getWaitlist(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getWaitlist(phno);
    }

    @DeleteMapping("/self/remove")
    public void removeFromWaitlist(@RequestParam long phno, @RequestParam int productId) {
        CustomerAccess.requireSelfOrService(phno);
        orderService.removeFromWaitlist(phno, productId);
    }
}
