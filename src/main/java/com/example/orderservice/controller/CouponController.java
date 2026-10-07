package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.CouponSuggestion;
import com.example.orderservice.entity.Coupon;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Every endpoint here except /available falls under SecurityConfig's default service-only rule, so managing
// coupons already requires the same X-Service-Key as every other trusted-caller action - no security config
// changes needed for this controller.
@RestController
@RequestMapping("/coupons")
public class CouponController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private com.example.orderservice.service.CouponBatchService batchService;

    // Mints a batch of single-use codes (see CouponBatchService); the response is where the codes are handed over.
    @PostMapping("/bulk")
    public com.example.orderservice.dto.BulkCouponResult addBulk(@RequestBody com.example.orderservice.dto.BulkCouponRequest request) {
        return batchService.generate(request);
    }

    @PostMapping("/add")
    public Coupon addCoupon(@RequestBody Coupon coupon) {
        return orderService.saveCoupon(coupon);
    }

    // Signed-in customer (own phone number only) or service key: coupons this customer can still use at checkout.
    @GetMapping("/available")
    public List<CouponSuggestion> getAvailable(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getAvailableCoupons(phno);
    }

    @GetMapping("/all")
    public List<Coupon> getAll() {
        return orderService.getCoupons();
    }
}
