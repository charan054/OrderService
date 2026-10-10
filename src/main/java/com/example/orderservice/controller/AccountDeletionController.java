package com.example.orderservice.controller;

import com.example.orderservice.dto.AccountDeletionConfirm;
import com.example.orderservice.dto.AccountDeletionPreview;
import com.example.orderservice.dto.AccountDeletionResult;
import com.example.orderservice.security.LoginRateLimiter;
import com.example.orderservice.service.AccountDeletionService;
import com.example.orderservice.service.CustomerAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// A signed-in customer deleting THEIR OWN account. Customer session only (SecurityConfig): the service key and admin
// accounts cannot use it, and the phone number always comes from the session, never from the request.
@RestController
@RequestMapping("/customer/account/delete")
public class AccountDeletionController {
    private final AccountDeletionService service;
    private final CustomerAuthService customerAuthService;
    private final LoginRateLimiter rateLimiter;

    public AccountDeletionController(AccountDeletionService service, CustomerAuthService customerAuthService,
                                     LoginRateLimiter rateLimiter) {
        this.service = service;
        this.customerAuthService = customerAuthService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/preview")
    public AccountDeletionPreview preview(@AuthenticationPrincipal Long phno) {
        return service.preview(phno);
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void request(@AuthenticationPrincipal Long phno, HttpServletRequest http) {
        rateLimiter.checkCodeRequest(http.getRemoteAddr(), phno, customerAuthService.boundEmail(phno).orElse(""));
        service.requestCode(phno);
    }

    @PostMapping("/confirm")
    public AccountDeletionResult confirm(@AuthenticationPrincipal Long phno, @RequestBody AccountDeletionConfirm body,
                                         HttpServletRequest http) {
        rateLimiter.checkVerify(http.getRemoteAddr(), phno);
        return service.confirm(phno, body.code(), body.acknowledgeForfeit());
    }
}
