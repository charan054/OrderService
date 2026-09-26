package com.example.orderservice.controller;

import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Public, same self-service trust level as /cart/byphno - looking up your own rollup by your own phone number.
@RestController
public class CustomerProfileController {
    @Autowired
    private OrderService orderService;

    @GetMapping("/customer/profile")
    public CustomerProfile getProfile(@RequestParam long phno) {
        return orderService.getCustomerProfile(phno);
    }
}
