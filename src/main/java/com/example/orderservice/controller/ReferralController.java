package com.example.orderservice.controller;

import com.example.orderservice.dto.ReferralInfo;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.ReferralService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Refer-a-friend. Both calls are for the signed-in customer's OWN phone number (or the service key) - see
// CustomerAccess and SecurityConfig.
@RestController
@RequestMapping("/referral")
public class ReferralController {
    @Autowired
    private ReferralService referralService;

    // The customer's referral card (their code, friends referred, points earned).
    @GetMapping("/mine")
    public ReferralInfo mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return referralService.getInfo(phno);
    }

    // A new customer entering a friend's code. Rejected with a clear message when it can't apply.
    @PostMapping("/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@RequestParam long phno, @RequestParam String code) {
        CustomerAccess.requireSelfOrService(phno);
        referralService.apply(phno, code);
    }
}
