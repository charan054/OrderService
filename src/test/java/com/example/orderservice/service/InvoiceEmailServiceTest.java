package com.example.orderservice.service;

import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.InvoiceEmailResult;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.CustomerAuthException;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.repository.CustomerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private OrderService orderService;
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private MailService mailService;

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private InvoiceEmailService service;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        service = new InvoiceEmailService(orderService, accounts, mailService, clock, 60, "UTC");
    }

    private Invoice invoice() {
        return new Invoice(7, NOW, "Asha", PHNO,
                List.of(new Invoice.Line(1, "Soap", 2, 30.0, 60.0, 1, 0)),
                "WELCOME10", 6.0, 10, 44.0, 20.0, "CASH", false, "PLACED", "1 Main St, Pune, MH, 411001", null, null);
    }

    private void verifiedEmail() {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(PHNO);
        a.setEmail("asha@example.com");
        when(accounts.findById(PHNO)).thenReturn(Optional.of(a));
    }

    @Test
    void emailsTheInvoiceToTheVerifiedAddressAndMasksItInTheReply() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(eq("asha@example.com"), eq("Your Charan Mart invoice for order #7"), anyString())).thenReturn(true);

        InvoiceEmailResult result = service.send(7, PHNO);

        assertEquals("a***@example.com", result.sentTo());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(anyString(), anyString(), body.capture());
        String text = body.getValue();
        assertTrue(text.contains("Order #7"));
        assertTrue(text.contains("2 x Soap  @ Rs. 30.00 = Rs. 60.00  (1 cancelled)"));
        assertTrue(text.contains("Coupon (WELCOME10): -Rs. 6.00"));
        assertTrue(text.contains("Loyalty points redeemed: -Rs. 10.00"));
        assertTrue(text.contains("Total charged: Rs. 44.00"));
        assertTrue(text.contains("Refunded or taken off since: Rs. 20.00"));
        assertTrue(text.contains("Ship to: 1 Main St, Pune, MH, 411001"));
    }

    @Test
    void anOrderThatIsNotThisCustomersIsNotFoundAndNothingIsSent() {
        when(orderService.getInvoice(7, PHNO)).thenThrow(new OrderNotFoundException("Order not found"));

        assertThrows(OrderNotFoundException.class, () -> service.send(7, PHNO));
        verify(mailService, never()).send(any(), any(), any());
    }

    @Test
    void aCustomerWithoutAVerifiedEmailGetsAClearConflict() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(mailService, never()).send(any(), any(), any());
    }

    @Test
    void theSameOrderCannotBeSentAgainInsideTheCooldownButCanAfterIt() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.send(7, PHNO);
        now.set(NOW.plusSeconds(30));
        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        now.set(NOW.plusSeconds(61));
        service.send(7, PHNO);

        verify(mailService, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void aFailedSendIsABadGatewayAndDoesNotStartTheCooldown() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false, true);

        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());

        service.send(7, PHNO); // immediate retry is allowed because nothing arrived
        verify(mailService, times(2)).send(anyString(), anyString(), anyString());
    }
}
