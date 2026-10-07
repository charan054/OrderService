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
        when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
}
