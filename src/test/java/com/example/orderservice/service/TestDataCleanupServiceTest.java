package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AccountDeletionResult;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CustomerAccount;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.LoyaltyAccountRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestDataCleanupServiceTest {
    @Mock
    private CartRepository carts;
    @Mock
    private CustomerAccountRepository accounts;
    @Mock
    private ShippingAddressRepository addresses;
    @Mock
    private StoreCreditAccountRepository credit;
    @Mock
    private LoyaltyAccountRepository loyalty;
    @Mock
    private AccountErasure erasure;
    @Mock
    private ProductClient productClient;

    private TestDataCleanupService service(boolean enabled, String prefix) {
        return new TestDataCleanupService(carts, accounts, addresses, credit, loyalty, erasure, productClient, enabled, prefix, "key");
    }

    private static Cart order(long phno) {
        Cart c = new Cart();
        c.setCustomerPhno(phno);
        return c;
    }

    @BeforeEach
    void setUp() {
        lenient().when(carts.findAll()).thenReturn(List.of(order(9000123456L), order(9000123456L), order(9876543210L), order(0L)));
        CustomerAccount account = new CustomerAccount();
        account.setPhno(9000999999L);
        lenient().when(accounts.findAll()).thenReturn(List.of(account));
        lenient().when(addresses.findAll()).thenReturn(List.of());
        lenient().when(credit.findAll()).thenReturn(List.of());
        lenient().when(loyalty.findAll()).thenReturn(List.of());
    }

    @Test
    void refusesToDoAnythingUnlessSwitchedOn() {
        assertThrows(ProductException.class, () -> service(false, "9000").preview());
        assertThrows(ProductException.class, () -> service(false, "9000").run(false));
        assertThrows(ProductException.class, () -> service(true, "90").preview());
        verify(erasure, never()).erase(anyLong(), anyInt());
    }

    @Test
    void previewListsOnlyNumbersInTheTestRange() {
        var preview = service(true, "9000").preview();

        assertEquals(List.of(9000123456L, 9000999999L), preview.customers().stream().map(TestDataCleanupService.Entry::phno).toList());
        assertEquals(2, preview.orders());
        assertTrue(preview.customers().get(1).hasAccount());
    }

    @Test
    void aDryRunChangesNothing() {
        var outcome = service(true, "9000").run(true);

        assertTrue(outcome.dryRun());
        assertEquals(2, outcome.customers());
        verify(erasure, never()).erase(anyLong(), anyInt());
        verify(productClient, never()).anonymiseReviews(org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void aRealRunErasesEachTestCustomerAndReportsTheOnesThatFailed() {
        when(productClient.anonymiseReviews("key", 9000123456L)).thenReturn(3);
        when(productClient.anonymiseReviews("key", 9000999999L)).thenThrow(new IllegalStateException("ProductService down"));
        when(erasure.erase(9000123456L, 3)).thenReturn(new AccountDeletionResult(2, 1, 0, 0, 0, 0, 3, 0, 0));

        var outcome = service(true, "9000").run(false);

        assertEquals(1, outcome.customers());
        assertEquals(2, outcome.ordersAnonymised());
        assertEquals(3, outcome.reviewsAnonymised());
        assertEquals(List.of(9000999999L), outcome.failed());
        verify(erasure, never()).erase(eq(9876543210L), anyInt());
        verify(erasure, never()).erase(eq(9000999999L), anyInt());
    }
}
