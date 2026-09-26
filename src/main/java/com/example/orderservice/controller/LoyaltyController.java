package com.example.orderservice.controller;

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

    // Public, same self-service trust level as /cart/byphno - looking up your own points balance by your own
    // phone number. Returns a zero-balance account (not 404) for a customer who hasn't earned any yet.
    @GetMapping("/byphno")
    public LoyaltyAccount getBalance(@RequestParam long phno) {
        return orderService.getLoyaltyAccount(phno);
    }

    // Same public trust level as /cart/{orderId}/tracking - a per-transaction history behind the single
    // balance figure above.
    @GetMapping("/history")
    public List<LoyaltyTransaction> getHistory(@RequestParam long phno) {
        return orderService.getLoyaltyHistory(phno);
    }

    // Falls under SecurityConfig's default "anyRequest().authenticated()" rule, same as CouponController - a
    // manual balance correction needs the same X-Service-Key as every other trusted-caller action.
    @PostMapping("/adjust")
    public LoyaltyAccount adjust(@RequestBody LoyaltyAdjustmentRequest request) {
        return orderService.adjustLoyaltyPoints(request.customerPhno(), request.points(), request.reason());
    }
}
