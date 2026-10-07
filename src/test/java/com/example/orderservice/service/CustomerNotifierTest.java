package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerNotifierTest {

    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private NotificationLogRepository notifications;
    @Mock
    private MailService mailService;

    private CustomerNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new CustomerNotifier(accounts, notifications, mailService, Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Cart order(PaymentMethod method, boolean paid) {
        Cart cart = new Cart();
        cart.setOrderId(42L);
        cart.setCustomerName("Asha");
        cart.setCustomerPhno(PHNO);
        cart.setTotalPrice(450);
        cart.setPaymentMethod(method);
        cart.setPaid(paid);
        return cart;
    }

    private void verifiedEmail(String email) {
        CustomerAccount account = new CustomerAccount();
        account.setPhno(PHNO);
        account.setEmail(email);
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account));
    }

    @Test
    void emailsTheVerifiedAddressWhenAnOrderShips() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        NotificationLog log = notifier.notifyStatusChange(order(PaymentMethod.PHONEPE, true), OrderStatus.SHIPPED);

        verify(mailService).send(eq("asha@example.com"), eq("Your order #42 has shipped"), contains("on its way"));
        assertTrue(log.isEmailed());
        assertEquals(42L, log.getOrderId());
        assertEquals(OrderStatus.SHIPPED, log.getEventType());
        assertEquals(NOW, log.getSentAt());
    }

    @Test
    void deliveredEmailHasItsOwnSubject() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        notifier.notifyStatusChange(order(PaymentMethod.PHONEPE, true), OrderStatus.DELIVERED);

        verify(mailService).send(eq("asha@example.com"), eq("Your order #42 was delivered"), anyString());
    }

    @Test
    void unpaidCashOrderReminderMentionsTheAmountToKeepReady() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        notifier.notifyStatusChange(order(PaymentMethod.CASH, false), OrderStatus.SHIPPED);

        verify(mailService).send(anyString(), anyString(), contains("cash on delivery - please keep Rs. 450.00 ready"));
    }

    @Test
    void stillRecordsTheNotificationWhenTheCustomerHasNoVerifiedEmail() {
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());

        NotificationLog log = notifier.notifyStatusChange(order(PaymentMethod.PHONEPE, true), OrderStatus.SHIPPED);

        verifyNoInteractions(mailService);
        assertFalse(log.isEmailed());
        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notifications).save(saved.capture());
        assertEquals("Your order #42 has shipped", saved.getValue().getMessage());
    }

    @Test
    void aFailedSendIsRecordedAsNotEmailedRatherThanThrown() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false);

        NotificationLog log = notifier.notifyStatusChange(order(PaymentMethod.PHONEPE, true), OrderStatus.DELIVERED);

        assertFalse(log.isEmailed());
    }

    @Test
    void placedEmailConfirmsTheOrderAndMentionsAnyCouponSaving() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        Cart cart = order(PaymentMethod.PHONEPE, true);
        cart.setDiscountAmount(50);

        NotificationLog log = notifier.notifyStatusChange(cart, OrderStatus.PLACED);

        verify(mailService).send(eq("asha@example.com"), eq("Your order #42 is confirmed"), contains("You saved Rs. 50.00"));
        assertEquals(OrderStatus.PLACED, log.getEventType());
    }

    @Test
    void placedEmailForAnUnpaidCashOrderTellsThemToKeepTheCashReady() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        notifier.notifyStatusChange(order(PaymentMethod.CASH, false), OrderStatus.PLACED);

        verify(mailService).send(anyString(), anyString(), contains("please keep Rs. 450.00 ready"));
    }

    @Test
    void cancelledEmailSaysHowMuchWasRefundedToPhonePe() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        Cart cart = order(PaymentMethod.PHONEPE, true);
        cart.setRefundedAmount(450);

        notifier.notifyStatusChange(cart, OrderStatus.CANCELLED);

        verify(mailService).send(eq("asha@example.com"), eq("Your order #42 was cancelled"),
                contains("Rs. 450.00 has been refunded to your PhonePe account"));
    }

    // A cash order or an unpaid UPI order never took money, so the email must not promise a refund.
    @Test
    void cancelledEmailForAnOrderThatWasNeverChargedPromisesNoRefundAndNeverAsksForCash() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);

        notifier.notifyStatusChange(order(PaymentMethod.CASH, false), OrderStatus.CANCELLED, "payment window expired");

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailService).send(anyString(), anyString(), body.capture());
        assertTrue(body.getValue().contains("Reason: payment window expired."));
        assertTrue(body.getValue().contains("You were not charged for this order."));
        assertFalse(body.getValue().contains("keep Rs."));
    }

    @Test
    void returnedEmailCarriesTheReasonAndTheRefund() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        Cart cart = order(PaymentMethod.PHONEPE, true);
        cart.setRefundedAmount(450);
        cart.setReturnReason("too small");

        notifier.notifyStatusChange(cart, OrderStatus.RETURNED);

        verify(mailService).send(eq("asha@example.com"), eq("Your return for order #42 is complete"), contains("Reason given: too small."));
        verify(mailService).send(anyString(), anyString(), contains("has been refunded to your PhonePe account"));
    }

    @Test
    void itemRefundEmailNamesTheItemAndTheAmountAndKeepsTheOrderStatusAsEventType() {
        verifiedEmail("asha@example.com");
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        Cart cart = order(PaymentMethod.PHONEPE, true);
        cart.setStatus(OrderStatus.PLACED);

        NotificationLog log = notifier.notifyItemRefund(cart, "cancelled", 7, 2, 90);

        verify(mailService).send(eq("asha@example.com"), eq("Order #42: 2 x product #7 cancelled"),
                contains("Rs. 90.00 has been refunded to your PhonePe account"));
        assertEquals(OrderStatus.PLACED, log.getEventType());
    }

    @Test
    void shippedEmailMentionsCarrierAndTrackingWhenRecorded() {
        com.example.orderservice.entity.Cart cart = order(PaymentMethod.PHONEPE, true);
        cart.setCarrier("Delhivery");
        cart.setTrackingNumber("DL123");

        String both = CustomerNotifier.bodyFor(cart, OrderStatus.SHIPPED);
        assertTrue(both.contains("Carrier: Delhivery, tracking number: DL123."));

        cart.setCarrier(null);
        assertTrue(CustomerNotifier.bodyFor(cart, OrderStatus.SHIPPED).contains("tracking number: DL123."));

        cart.setTrackingNumber(null);
        assertFalse(CustomerNotifier.bodyFor(cart, OrderStatus.SHIPPED).contains("Carrier"));
    }
}
