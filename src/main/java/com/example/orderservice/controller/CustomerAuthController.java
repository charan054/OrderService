package com.example.orderservice.controller;

import com.example.orderservice.dto.CustomerLoginResponse;
import com.example.orderservice.dto.CustomerSessionInfo;
import com.example.orderservice.dto.LoginCodeRequest;
import com.example.orderservice.dto.LoginVerifyRequest;
import com.example.orderservice.security.CustomerTokenAuthenticationFilter;
import com.example.orderservice.service.CustomerAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer")
public class CustomerAuthController {
    private final CustomerAuthService customerAuthService;

    public CustomerAuthController(CustomerAuthService customerAuthService) {
        this.customerAuthService = customerAuthService;
    }

    // Public - the storefront's sign-in step 1. Always 204 for a valid phone+email (see requestCode for why).
    @PostMapping("/login/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestCode(@RequestBody LoginCodeRequest request) {
        customerAuthService.requestCode(request.phno(), request.email());
    }

    // Public - step 2: exchanges the emailed code for a session token.
    @PostMapping("/login/verify")
    public CustomerLoginResponse verify(@RequestBody LoginVerifyRequest request) {
        return customerAuthService.verifyCode(request.phno(), request.code());
    }

    // Public so an already-expired token can still "log out" cleanly - it only ever deletes the caller's own token.
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = CustomerTokenAuthenticationFilter.HEADER, required = false) String token) {
        customerAuthService.logout(token);
    }

    // Customer-only: who this token belongs to. shop.html calls it on page load to check a saved token still works.
    @GetMapping("/session")
    public CustomerSessionInfo session(@AuthenticationPrincipal Long phno) {
        return new CustomerSessionInfo(phno, customerAuthService.boundEmail(phno).orElse(null));
    }

    // Admin-only (X-Service-Key): rebind a phone to a different email - see CustomerAuthService.adminSetEmail.
    @PutMapping("/admin/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminSetEmail(@RequestParam long phno, @RequestParam String email) {
        customerAuthService.adminSetEmail(phno, email);
    }
}
