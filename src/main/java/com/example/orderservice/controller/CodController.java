package com.example.orderservice.controller;

import com.example.orderservice.dto.CodEligibility;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.CodRiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Cash-on-delivery eligibility. /cod is the signed-in customer's own (so the storefront can grey out the option
// before checkout); /admin/cod is service-key only (SecurityConfig default) and lets an admin override the rules.
@RestController
@RequestMapping("/customer")
public class CodController {
    @Autowired
    private CodRiskService codRiskService;

    @GetMapping("/cod")
    public CodEligibility mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return codRiskService.check(phno);
    }

    @GetMapping("/admin/cod")
    public CodEligibility adminCheck(@RequestParam long phno) {
        return codRiskService.check(phno);
    }

    // mode = AUTO (remove the override), ALLOW or BLOCK.
    @PutMapping("/admin/cod")
    public CodEligibility adminSet(@RequestParam long phno, @RequestParam String mode,
                                   @RequestParam(required = false) String note) {
        return codRiskService.setOverride(phno, mode, note);
    }
}
