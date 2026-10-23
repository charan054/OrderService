package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.dto.MandateDtos;
import com.example.orderservice.dto.PhonepeLoginRequest;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * The shop's side of PhonePe auto-pay (mandates, kept by PhonepayService): a customer gives permission once, with their PIN
 * and limits, and subscription deliveries are then charged to their PhonePe account without asking each time. The PIN is
 * passed straight to PhonePe to log in and is never stored here; listing and revoking only need the customer's verified
 * storefront session, because both are safe for the customer.
 */
@Service
public class AutopayMandateService {
    private final PhonepeClient phonepe;
    private final String serviceKey;
    private final Clock clock;

    public AutopayMandateService(PhonepeClient phonepe, @Value("${internal.service.api-key}") String serviceKey, Clock clock) {
        this.phonepe = phonepe;
        this.serviceKey = serviceKey;
        this.clock = clock;
    }

    public MandateDtos.View setUp(MandateDtos.SetUp request) {
        if (request.pin() == null || request.pin().isBlank()) {
            throw new ProductException("Enter your PhonePe PIN to allow auto-pay");
        }
        String authorization;
        try {
            PhonepeLoginResponse login = phonepe.login(new PhonepeLoginRequest(request.phno(), request.pin()));
            authorization = "Bearer " + login.token();
        } catch (FeignException e) {
            throw paymentError(e);
        }
        try {
            return phonepe.createMandate(authorization,
                    new MandateDtos.CreateRequest(request.perChargeLimit(), request.monthlyLimit(), request.validDays(), request.pin()));
        } catch (FeignException e) {
            throw paymentError(e);
        }
    }

    public List<MandateDtos.View> mine(long phno) {
        try {
            return phonepe.listMandates(serviceKey, phno);
        } catch (FeignException e) {
            throw paymentError(e);
        }
    }

    public MandateDtos.View revoke(long phno, long mandateId) {
        try {
            return phonepe.revokeMandate(serviceKey, mandateId, phno);
        } catch (FeignException e) {
            throw paymentError(e);
        }
    }

    /** A subscription may only use a mandate that belongs to the same customer, is active and has not ended. */
    public void requireUsable(long mandateId, long phno) {
        MandateDtos.View mandate;
        try {
            mandate = phonepe.getMandate(serviceKey, mandateId);
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw new ProductException("That auto-pay permission does not exist");
            }
            throw paymentError(e);
        }
        if (mandate.payerPhno() != phno) {
            throw new ProductException("That auto-pay permission does not exist");     // never reveal someone else's
        }
        if (!"ACTIVE".equals(mandate.status()) || mandate.validUntil() == null || !mandate.validUntil().isAfter(clock.instant())) {
            throw new ProductException("That auto-pay permission has been revoked or has ended");
        }
    }

    private static PaymentException paymentError(FeignException e) {
        HttpStatus status = HttpStatus.resolve(e.status());
        return new PaymentException(status != null ? status : HttpStatus.BAD_GATEWAY, e.contentUTF8());
    }
}
