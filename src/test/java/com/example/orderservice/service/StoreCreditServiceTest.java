package com.example.orderservice.service;

import com.example.orderservice.dto.StoreCreditSummary;
import com.example.orderservice.entity.StoreCreditAccount;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoreCreditServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Mock
    private StoreCreditAccountRepository accounts;
    @Mock
    private StoreCreditTransactionRepository transactions;

    private StoreCreditService service;

    @BeforeEach
    void setUp() {
        service = new StoreCreditService(accounts, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(transactions.save(any(StoreCreditTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private StoreCreditAccount account(double balance) {
        StoreCreditAccount a = new StoreCreditAccount();
        a.setPhno(PHNO);
        a.setBalance(balance);
        when(accounts.lockByPhno(PHNO)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void creditingANewCustomerOpensTheirBalance() {
        when(accounts.lockByPhno(PHNO)).thenReturn(Optional.empty());

        StoreCreditTransaction tx = service.credit(PHNO, 150.456, StoreCreditTransaction.Type.REFUND, 42L, "Refund for order #42");

        assertEquals(150.46, tx.getAmount());
        assertEquals(150.46, tx.getBalanceAfter());
        assertEquals(42L, tx.getOrderId());
        assertEquals(StoreCreditTransaction.Type.REFUND, tx.getType());
        assertEquals(NOW, tx.getCreatedAt());
        verify(accounts).save(any(StoreCreditAccount.class));
    }

    @Test
    void spendingTakesItOffAndRecordsANegativeRow() {
        StoreCreditAccount a = account(200);

        StoreCreditTransaction tx = service.spend(PHNO, 75, null, "Used at checkout");

        assertEquals(125.0, a.getBalance());
        assertEquals(-75.0, tx.getAmount());
        assertEquals(125.0, tx.getBalanceAfter());
        assertEquals(StoreCreditTransaction.Type.SPENT, tx.getType());
    }

    @Test
    void spendingMoreThanTheBalanceChangesNothing() {
        account(50);

        assertThrows(ProductException.class, () -> service.spend(PHNO, 50.01, null, "x"));
        assertThrows(ProductException.class, () -> service.spend(PHNO, 0, null, "x"));
        verify(accounts, never()).save(any());
        verify(transactions, never()).save(any());
    }

    @Test
    void spendingWithNoAccountAtAllIsRefused() {
        when(accounts.lockByPhno(PHNO)).thenReturn(Optional.empty());

        assertThrows(ProductException.class, () -> service.spend(PHNO, 1, null, "x"));
    }

    @Test
    void adjustmentsNeedANoteAndCannotTakeTheBalanceNegative() {
        StoreCreditAccount a = account(100);
        when(transactions.findTop50ByPhnoOrderByIdDesc(PHNO)).thenReturn(List.of());
        when(accounts.findById(PHNO)).thenReturn(Optional.of(a));

        StoreCreditSummary after = service.adjust(PHNO, -40, " goodwill correction ");

        assertEquals(60.0, after.balance());
        assertThrows(ProductException.class, () -> service.adjust(PHNO, -60.01, "too much"));
        assertThrows(ProductException.class, () -> service.adjust(PHNO, 10, "  "));
        assertThrows(ProductException.class, () -> service.adjust(PHNO, 0, "zero"));
        assertThrows(ProductException.class, () -> service.adjust(PHNO, 100001, "huge"));
    }

    @Test
    void theAdjustmentNoteIsTrimmedAndKept() {
        account(0);
        when(accounts.findById(PHNO)).thenReturn(Optional.empty());
        when(transactions.findTop50ByPhnoOrderByIdDesc(PHNO)).thenReturn(List.of());
        org.mockito.ArgumentCaptor<StoreCreditTransaction> tx = org.mockito.ArgumentCaptor.forClass(StoreCreditTransaction.class);

        service.adjust(PHNO, 25, "  sorry for the delay  ");

        verify(transactions).save(tx.capture());
        assertEquals("sorry for the delay", tx.getValue().getNote());
        assertEquals(StoreCreditTransaction.Type.ADJUSTED, tx.getValue().getType());
    }

    @Test
    void linkOrderFillsInTheOrderIdOnTheCheckoutRow() {
        StoreCreditTransaction tx = new StoreCreditTransaction();

        service.linkOrder(tx, 77L);
        service.linkOrder(null, 78L);

        assertEquals(77L, tx.getOrderId());
        verify(transactions).save(tx);
    }
}
