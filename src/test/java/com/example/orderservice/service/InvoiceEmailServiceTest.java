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
        service = new InvoiceEmailService(orderService, accounts, mailService, new InvoicePdfService("UTC", "Charan Mart"), clock, 60, "UTC");
    }

    private Invoice invoice() {
        return new Invoice(7, NOW, "Asha", PHNO,
                List.of(new Invoice.Line(1, "Soap", 2, 30.0, 60.0, 1, 0)),
                "WELCOME10", 6.0, 10, 0.0, 44.0, 20.0, "CASH", false, "PLACED", "1 Main St, Pune, MH, 411001", null, null,
                null, null, null);
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
        when(mailService.send(eq("asha@example.com"), eq("Your Charan Mart invoice for order #7"), anyString(), anyString(), any(byte[].class), eq("application/pdf"))).thenReturn(true);

        InvoiceEmailResult result = service.send(7, PHNO);

        assertEquals("a***@example.com", result.sentTo());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(anyString(), anyString(), body.capture(), anyString(), any(byte[].class), anyString());
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
    void theEmailCarriesTheInvoiceNumberAndTheGstSummary() {
        Invoice base = invoice();
        Invoice.Tax tax = new Invoice.Tax("Charan Mart", "29ABCDE1234F1Z5", "Karnataka", "Karnataka", false,
                List.of(new Invoice.TaxLine(1, "Soap", "3401", 18, 1, 25.42, 2.29, 2.29, 0, 30)),
                25.42, 2.29, 2.29, 0, 4.58);
        Invoice withTax = new Invoice(base.orderId(), base.placedAt(), base.customerName(), base.customerPhno(), base.lines(),
                base.couponCode(), base.discountAmount(), base.pointsRedeemed(), base.storeCreditUsed(), base.totalPrice(),
                base.refundedAmount(), base.paymentMethod(), base.paid(), base.status(), base.shippingAddress(),
                base.deliveryNote(), base.deliverySlot(), "CM/2026-27/000042", NOW, tax);

        String text = service.body(withTax);

        assertTrue(text.contains("Tax invoice CM/2026-27/000042, 7 Oct 2026, 10:00"));
        assertTrue(text.contains("seller GSTIN 29ABCDE1234F1Z5"));
        assertTrue(text.contains("Taxable value: Rs. 25.42"));
        assertTrue(text.contains("CGST: Rs. 2.29 | SGST: Rs. 2.29"));
    }

    @Test
    void anOrderThatIsNotThisCustomersIsNotFoundAndNothingIsSent() {
        when(orderService.getInvoice(7, PHNO)).thenThrow(new OrderNotFoundException("Order not found"));

        assertThrows(OrderNotFoundException.class, () -> service.send(7, PHNO));
        verify(mailService, never()).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCustomerWithoutAVerifiedEmailGetsAClearConflict() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(mailService, never()).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theSameOrderCannotBeSentAgainInsideTheCooldownButCanAfterIt() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyString())).thenReturn(true);

        service.send(7, PHNO);
        now.set(NOW.plusSeconds(30));
        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        now.set(NOW.plusSeconds(61));
        service.send(7, PHNO);

        verify(mailService, times(2)).send(anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyString());
    }

    @Test
    void aFailedSendIsABadGatewayAndDoesNotStartTheCooldown() {
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyString())).thenReturn(false, true);

        CustomerAuthException e = assertThrows(CustomerAuthException.class, () -> service.send(7, PHNO));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());

        service.send(7, PHNO); // immediate retry is allowed because nothing arrived
        verify(mailService, times(2)).send(anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyString());
    }

    @Test
    void thePdfRidesAlongAsAnAttachmentNamedAfterTheInvoice() {
        Invoice base = invoice();
        Invoice numbered = new Invoice(base.orderId(), base.placedAt(), base.customerName(), base.customerPhno(), base.lines(),
                base.couponCode(), base.discountAmount(), base.pointsRedeemed(), base.storeCreditUsed(), base.totalPrice(),
                base.refundedAmount(), base.paymentMethod(), base.paid(), base.status(), base.shippingAddress(),
                base.deliveryNote(), base.deliverySlot(), "CM/2026-27/000042", NOW, null);
        when(orderService.getInvoice(7, PHNO)).thenReturn(numbered);
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyString())).thenReturn(true);

        service.send(7, PHNO);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(mailService).send(anyString(), anyString(), text.capture(), fileName.capture(), pdf.capture(), eq("application/pdf"));
        assertEquals("invoice-CM-2026-27-000042.pdf", fileName.getValue());
        assertEquals("%PDF", new String(pdf.getValue(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(text.getValue().contains("also attached as a PDF"));
    }

    @Test
    void ifThePdfCannotBeRenderedTheTextInvoiceStillGoesOut() {
        InvoicePdfService broken = org.mockito.Mockito.mock(InvoicePdfService.class);
        when(broken.render(any())).thenThrow(new IllegalStateException("boom"));
        InvoiceEmailService fallback = new InvoiceEmailService(orderService, accounts, mailService, broken,
                Clock.fixed(NOW, ZoneOffset.UTC), 60, "UTC");
        when(orderService.getInvoice(7, PHNO)).thenReturn(invoice());
        verifiedEmail();
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        fallback.send(7, PHNO);

        verify(mailService).send(eq("asha@example.com"), anyString(), anyString());
        verify(mailService, never()).send(any(), any(), any(), any(), any(), any());
    }
}
