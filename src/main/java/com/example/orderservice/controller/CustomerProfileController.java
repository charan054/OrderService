package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Signed-in customer (own phone number only, see CustomerAccess) or service key.
@RestController
public class CustomerProfileController {
    @Autowired
    private OrderService orderService;

    @GetMapping("/customer/profile")
    public CustomerProfile getProfile(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getCustomerProfile(phno);
    }
}
