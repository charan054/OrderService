package com.example.orderservice.controller;

import com.example.orderservice.dto.CouponSuggestion;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Every endpoint here except /available falls under SecurityConfig's default "anyRequest().authenticated()" rule, so managing
// coupons already requires the same X-Service-Key as every other trusted-caller action - no security config
// changes needed for this controller.
@RestController
@RequestMapping("/coupons")
public class CouponController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public Coupon addCoupon(@RequestBody Coupon coupon) {
        return orderService.saveCoupon(coupon);
    }

    // Public (see SecurityConfig): lets the storefront suggest coupons the customer can actually use at checkout.
    @GetMapping("/available")
    public List<CouponSuggestion> getAvailable(@RequestParam long phno) {
        return orderService.getAvailableCoupons(phno);
    }

    @GetMapping("/all")
    public List<Coupon> getAll() {
        return orderService.getCoupons();
    }
}
