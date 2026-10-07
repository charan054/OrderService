package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.LoyaltyAdjustmentRequest;
import com.example.orderservice.entity.LoyaltyAccount;
import com.example.orderservice.entity.LoyaltyTransaction;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/loyalty")
public class LoyaltyController {
    @Autowired
    private OrderService orderService;

    // Signed-in customer (own phone number only) or service key. Returns a zero-balance account (not 404) for a customer who hasn't earned any yet.
    @GetMapping("/byphno")
    public LoyaltyAccount getBalance(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getLoyaltyAccount(phno);
    }

    // Same access as /byphno above - the per-transaction history behind the balance.
    @GetMapping("/history")
    public List<LoyaltyTransaction> getHistory(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getLoyaltyHistory(phno);
    }

    // Falls under SecurityConfig's default "anyRequest().authenticated()" rule, same as CouponController - a
    // manual balance correction needs the same X-Service-Key as every other trusted-caller action.
    @PostMapping("/adjust")
    public LoyaltyAccount adjust(@RequestBody LoyaltyAdjustmentRequest request) {
        return orderService.adjustLoyaltyPoints(request.customerPhno(), request.points(), request.reason());
    }
}
