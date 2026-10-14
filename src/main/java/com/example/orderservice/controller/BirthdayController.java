package com.example.orderservice.controller;

import com.example.orderservice.dto.BirthdayCouponResult;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.BirthdayCouponService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// /customer/birthday belongs to the signed-in customer (or the service key); running the job is service-key only.
@RestController
public class BirthdayController {
    private final BirthdayCouponService service;

    public BirthdayController(BirthdayCouponService service) {
        this.service = service;
    }

    // {"birthday": "MM-DD"}, or "" when none is saved
    @GetMapping("/customer/birthday")
    public Map<String, String> get(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return Map.of("birthday", service.get(phno).orElse(""));
    }

    @PutMapping("/customer/birthday")
    public Map<String, String> set(@RequestParam long phno, @RequestParam int month, @RequestParam int day) {
        CustomerAccess.requireSelfOrService(phno);
        return Map.of("birthday", service.set(phno, month, day));
    }

    @DeleteMapping("/customer/birthday")
    public void clear(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        service.clear(phno);
    }

    // Run the birthday-coupon job now (service key by default). Each customer gets at most one coupon per year.
    @PostMapping("/cart/birthday-coupons/run")
    public BirthdayCouponResult run() {
        return service.run();
    }
}
