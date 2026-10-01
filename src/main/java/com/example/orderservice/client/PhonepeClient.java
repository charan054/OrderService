package com.example.orderservice.client;

import com.example.orderservice.dto.CreateUpiCollectRequest;
import com.example.orderservice.dto.PaymentRequest;
import com.example.orderservice.dto.PaymentResponse;
import com.example.orderservice.dto.PhonepeLoginRequest;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.dto.RefundRequest;
import com.example.orderservice.dto.UpiCollectRequestResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

// Charges (and refunds) the buyer for an order through PhonepayService, using the buyer's OWN session token
// (the same one they'd use to call PhonepayService directly) - never a phone number we supply ourselves.
// PhonepayService reads the caller's identity only from that token, so this is always the buyer's own money
// moving, not OrderService acting on their behalf.
@FeignClient(name = "PhonepayService", url = "${phonepe.service.url}")
public interface PhonepeClient {
    @PostMapping("/phonepe/makepayment")
    PaymentResponse makePayment(@RequestHeader("Authorization") String authorization, @RequestBody PaymentRequest request);

    @PostMapping("/phonepe/transactions/{transactionId}/refund")
    PaymentResponse refund(@RequestHeader("Authorization") String authorization,
                           @PathVariable long transactionId, @RequestBody RefundRequest request);

    // Lets a checkout that only has the buyer's phone+PIN (the storefront flow) obtain the same session token a
    // buyer who already had one would supply directly - PhonepayService's own credential check, nothing verified
    // here.
    @PostMapping("/phonepe/login")
    PhonepeLoginResponse login(@RequestBody PhonepeLoginRequest request);

    // Merchant-only on PhonepayService's side (X-Service-Key, not a buyer token) - OrderService asking to
    // collect a payment from a buyer's UPI ID, rather than charging a token OrderService itself never holds.
    // See OrderService.order()'s payerUpiId path and checkPendingPayment().
    @PostMapping("/phonepe/upi/collect")
    UpiCollectRequestResponse createUpiCollectRequest(@RequestHeader("X-Service-Key") String serviceKey,
                                                       @RequestBody CreateUpiCollectRequest request);

    @GetMapping("/phonepe/upi/collect/{merchantReference}")
    UpiCollectRequestResponse getUpiCollectRequest(@RequestHeader("X-Service-Key") String serviceKey,
                                                    @PathVariable String merchantReference);
}
