package com.example.orderservice.service;

import com.example.orderservice.dto.AdminCustomerLookup;
import com.example.orderservice.dto.CustomerProfile;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.entity.LoyaltyTier;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCustomerServiceTest {
    private static final long PHNO = 9876543210L;

    @Mock
    private CartRepository orders;
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private ShippingAddressRepository addresses;
    @Mock
    private OrderService orderService;

    private AdminCustomerService service;

    @BeforeEach
    void setUp() {
        service = new AdminCustomerService(orders, accounts, addresses, orderService);
    }

    private Cart order(long id, OrderStatus status, double total, double refunded) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(PHNO);
        c.setStatus(status);
        c.setPaymentMethod(PaymentMethod.CASH);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        return c;
    }

    private CustomerAccount account(String email) {
        CustomerAccount a = new CustomerAccount();
        a.setPhno(PHNO);
        a.setEmail(email);
        a.setVerifiedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return a;
    }

    private void stubProfile() {
        when(orderService.getCustomerProfile(PHNO))
                .thenReturn(new CustomerProfile(PHNO, 0, 3, 2, LoyaltyTier.SILVER, 120, 700));
    }

    @Test
    void lookupByPhoneRollsUpOrdersSpendAndProfile() {
        stubProfile();
        when(accounts.findById(PHNO)).thenReturn(Optional.of(account("a@example.com")));
        when(orders.findBycustomerPhno(PHNO)).thenReturn(List.of(
                order(1, OrderStatus.DELIVERED, 500, 100),
                order(2, OrderStatus.CANCELLED, 300, 300),
                order(3, OrderStatus.PLACED, 200, 0),
                order(4, OrderStatus.PENDING_PAYMENT, 999, 0)));
        ShippingAddress addr = new ShippingAddress();
        addr.setId(5L);
        addr.setLabel("Home");
        addr.setLine1("1 Main St");
        addr.setCity("Pune");
        addr.setState("MH");
        addr.setPincode("411001");
        addr.setDefault(true);
        when(addresses.findByCustomerPhno(PHNO)).thenReturn(List.of(addr));

        AdminCustomerLookup r = service.lookup(PHNO, null);

        assertEquals("a@example.com", r.email());
        assertEquals(4, r.totalOrders());
        assertEquals(600.0, r.netSpend());
        assertEquals(1, r.ordersByStatus().get("CANCELLED"));
        assertEquals(List.of(4L, 3L, 2L, 1L), r.recentOrders().stream().map(AdminCustomerLookup.RecentOrder::orderId).toList());
        assertEquals(3, r.wishlistCount());
        assertEquals(LoyaltyTier.SILVER, r.loyaltyTier());
        assertEquals("1 Main St, Pune, MH, 411001", r.addresses().get(0).address());
    }

    @Test
    void recentOrdersAreCappedAtTen() {
        stubProfile();
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());
        List<Cart> many = new ArrayList<>();
        for (long i = 1; i <= 15; i++) many.add(order(i, OrderStatus.PLACED, 10, 0));
        when(orders.findBycustomerPhno(PHNO)).thenReturn(many);

        AdminCustomerLookup r = service.lookup(PHNO, null);

        assertEquals(10, r.recentOrders().size());
        assertEquals(15L, r.recentOrders().get(0).orderId());
        assertEquals(15, r.totalOrders());
    }

    @Test
    void aPhoneThatNeverSignedInHasNoEmail() {
        stubProfile();
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());
        when(orders.findBycustomerPhno(PHNO)).thenReturn(List.of());

        AdminCustomerLookup r = service.lookup(PHNO, null);

        assertNull(r.email());
        assertNull(r.emailVerifiedAt());
        assertEquals(0.0, r.netSpend());
    }

    @Test
    void lookupByEmailIsCaseInsensitive() {
        stubProfile();
        when(accounts.findAll()).thenReturn(List.of(account("Ann@Example.com")));
        when(orders.findBycustomerPhno(PHNO)).thenReturn(List.of());

        assertEquals(PHNO, service.lookup(null, " ann@example.COM ").phno());
    }

    @Test
    void unknownEmailIsNotFound() {
        when(accounts.findAll()).thenReturn(List.of(account("ann@example.com")));

        assertThrows(OrderNotFoundException.class, () -> service.lookup(null, "bob@example.com"));
    }

    @Test
    void exactlyOneOfPhoneOrEmailIsRequired() {
        assertThrows(ProductException.class, () -> service.lookup(null, null));
        assertThrows(ProductException.class, () -> service.lookup(PHNO, "a@example.com"));
        verifyNoInteractions(orders);
    }
}
