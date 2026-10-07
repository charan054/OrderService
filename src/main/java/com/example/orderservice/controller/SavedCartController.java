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

    @GetMapping
    public SavedCart get(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.get(phno);
    }

    @PutMapping
    public SavedCart replace(@RequestParam long phno, @RequestBody List<SavedCart.Line> lines) {
        CustomerAccess.requireSelfOrService(phno);
        return service.replace(phno, lines);
    }
}
