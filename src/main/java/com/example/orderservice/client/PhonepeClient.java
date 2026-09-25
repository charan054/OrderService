package com.example.orderservice.client;

import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

// Charges the buyer for an order through PhonepayService, using the buyer's OWN session token (the same one
// they'd use to call PhonepayService directly) - never a phone number we supply ourselves. PhonepayService reads
// the caller's identity only from that token, so this is the buyer paying, not OrderService acting on their behalf.
@FeignClient(name = "PhonepayService", url = "${phonepe.service.url}")
public interface PhonepeClient {
    @PostMapping("/phonepe/makepayment")
    PaymentResponse makePayment(@RequestHeader("Authorization") String authorization, @RequestBody PaymentRequest request);
}
