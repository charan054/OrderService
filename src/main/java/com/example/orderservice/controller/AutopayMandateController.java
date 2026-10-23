package com.example.orderservice.controller;

import com.example.orderservice.dto.MandateDtos;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.AutopayMandateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// PhonePe auto-pay for subscriptions. A signed-in customer (or the service key) manages their own mandates only.
@RestController
@RequestMapping("/cart/mandates")
public class AutopayMandateController {
    private final AutopayMandateService service;

    public AutopayMandateController(AutopayMandateService service) {
        this.service = service;
    }

    @PostMapping
    public MandateDtos.View setUp(@RequestBody MandateDtos.SetUp request) {
        CustomerAccess.requireSelfOrService(request.phno());
        return service.setUp(request);
    }

    @GetMapping
    public List<MandateDtos.View> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.mine(phno);
    }

    @DeleteMapping("/{id}")
    public MandateDtos.View revoke(@PathVariable long id, @RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.revoke(phno, id);
    }
}
