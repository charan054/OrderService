package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.dto.MandateDtos;
import com.example.orderservice.dto.PhonepeLoginResponse;
import com.example.orderservice.exception.PaymentException;
import com.example.orderservice.exception.ProductException;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutopayMandateServiceTest {
    private static final long PHNO = 9876543210L;
    private static final String KEY = "service-key";
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private PhonepeClient phonepe;
    private AutopayMandateService service;

    @BeforeEach
    void setUp() {
        phonepe = mock(PhonepeClient.class);
        service = new AutopayMandateService(phonepe, KEY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FeignException failure(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "http://phonepe/x", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("call", feign.Response.builder().status(status).reason("x").request(request)
                .headers(Collections.emptyMap()).body(body, StandardCharsets.UTF_8).build());
    }

    private static MandateDtos.View view(long payer, String status, Instant validUntil) {
        return new MandateDtos.View(5L, payer, "Charan Mart", new BigDecimal("1000"), new BigDecimal("1500"), BigDecimal.ZERO,
                new BigDecimal("1500"), validUntil, status, NOW, null);
    }

    @Test
    void settingUpLogsTheCustomerInWithTheirPinThenCreatesTheMandateWithTheirSession() {
        when(phonepe.login(any())).thenReturn(new PhonepeLoginResponse("tok", Instant.now(), PHNO, "ASHA"));
        MandateDtos.View created = view(PHNO, "ACTIVE", NOW.plusSeconds(86400));
        when(phonepe.createMandate(eq("Bearer tok"), any())).thenReturn(created);

        MandateDtos.View result = service.setUp(new MandateDtos.SetUp(PHNO, "1234", new BigDecimal("1000"), new BigDecimal("1500"), 30));

        assertEquals(created, result);
        verify(phonepe).createMandate(eq("Bearer tok"), any());
    }

    @Test
    void aWrongPinIsRelayedAndNothingIsCreated() {
        when(phonepe.login(any())).thenThrow(failure(401, "Invalid phone number or PIN"));

        PaymentException e = assertThrows(PaymentException.class,
                () -> service.setUp(new MandateDtos.SetUp(PHNO, "0000", new BigDecimal("1000"), new BigDecimal("1500"), 30)));

        assertEquals(401, e.getStatus().value());
        verify(phonepe, never()).createMandate(any(), any());
    }

    @Test
    void aMissingPinIsRefusedBeforePhonePeIsAsked() {
        assertThrows(ProductException.class, () -> service.setUp(new MandateDtos.SetUp(PHNO, " ", BigDecimal.ONE, BigDecimal.ONE, 1)));
        org.mockito.Mockito.verifyNoInteractions(phonepe);
    }

    @Test
    void listAndRevokeUseTheShopsKeyForThatCustomerOnly() {
        when(phonepe.listMandates(KEY, PHNO)).thenReturn(List.of(view(PHNO, "ACTIVE", NOW.plusSeconds(60))));
        when(phonepe.revokeMandate(KEY, 5L, PHNO)).thenReturn(view(PHNO, "REVOKED", NOW.plusSeconds(60)));

        assertEquals(1, service.mine(PHNO).size());
        assertEquals("REVOKED", service.revoke(PHNO, 5L).status());
    }

    @Test
    void aSubscriptionMayOnlyUseAnActiveUnexpiredMandateOfTheSameCustomer() {
        when(phonepe.getMandate(KEY, 5L)).thenReturn(view(PHNO, "ACTIVE", NOW.plusSeconds(3600)));
        service.requireUsable(5L, PHNO);                                              // fine

        assertThrows(ProductException.class, () -> service.requireUsable(5L, PHNO + 1));          // someone else's: looks like it does not exist

        when(phonepe.getMandate(KEY, 6L)).thenReturn(view(PHNO, "REVOKED", NOW.plusSeconds(3600)));
        assertThrows(ProductException.class, () -> service.requireUsable(6L, PHNO));

        when(phonepe.getMandate(KEY, 7L)).thenReturn(view(PHNO, "ACTIVE", NOW.minusSeconds(1)));
        assertThrows(ProductException.class, () -> service.requireUsable(7L, PHNO));

        when(phonepe.getMandate(eq(KEY), eq(8L))).thenThrow(failure(404, "Mandate not found"));
        assertThrows(ProductException.class, () -> service.requireUsable(8L, PHNO));
    }
}
