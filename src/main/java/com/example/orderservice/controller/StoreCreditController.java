package com.example.orderservice.controller;

import com.example.orderservice.dto.StoreCreditSummary;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.StoreCreditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Store credit. /byphno is the signed-in customer's own balance and history (or the service key's view of anyone's);
// /adjust is service-key only (SecurityConfig default) - goodwill credit or a correction, always with a note.
@RestController
@RequestMapping("/storecredit")
public class StoreCreditController {
    @Autowired
    private StoreCreditService storeCreditService;

    @GetMapping("/byphno")
    public StoreCreditSummary byPhno(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return storeCreditService.summary(phno);
    }

    @PostMapping("/adjust")
    public StoreCreditSummary adjust(@RequestParam long phno, @RequestParam double amount, @RequestParam String note) {
        return storeCreditService.adjust(phno, amount, note);
    }
}
