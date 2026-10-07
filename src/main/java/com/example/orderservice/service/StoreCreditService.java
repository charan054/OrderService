package com.example.orderservice.service;

import com.example.orderservice.dto.StoreCreditSummary;
import com.example.orderservice.entity.StoreCreditAccount;
import com.example.orderservice.entity.StoreCreditTransaction;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.StoreCreditAccountRepository;
import com.example.orderservice.repository.StoreCreditTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Locale;

/**
 * Store credit: an in-store rupee balance per phone number. It is filled by refunds the customer chose to take as
 * credit, by refunds of collected cash, by the credit an order used coming back on cancel/return, and by admin
 * adjustments; it is spent at checkout (see OrderService.order()). The balance can never go below zero, and every
 * change is written to StoreCreditTransaction with the balance after it.
 */
@Service
public class StoreCreditService {
    static final double MAX_ADJUSTMENT = 100000;

    private final StoreCreditAccountRepository accounts;
    private final StoreCreditTransactionRepository transactions;
    private final Clock clock;

    public StoreCreditService(StoreCreditAccountRepository accounts, StoreCreditTransactionRepository transactions,
                              Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    public double balance(long phno) {
        return accounts.findById(phno).map(StoreCreditAccount::getBalance).orElse(0.0);
    }

    public StoreCreditSummary summary(long phno) {
        return new StoreCreditSummary(phno, balance(phno), transactions.findTop50ByPhnoOrderByIdDesc(phno));
    }

    /** Takes amount off the balance under a row lock; fails (nothing changes) if the balance is too low. */
    @Transactional
    public StoreCreditTransaction spend(long phno, double amount, Long orderId, String note) {
        double value = money(amount);
        if (value <= 0) {
            throw new ProductException("Store credit to use must be more than zero");
        }
        StoreCreditAccount account = accounts.lockByPhno(phno).orElse(null);
        double balance = account == null ? 0 : account.getBalance();
        if (value > balance + 0.0001) {
            throw new ProductException("You only have Rs. " + format(balance) + " store credit");
        }
        return apply(account, -value, StoreCreditTransaction.Type.SPENT, orderId, note);
    }

    /** Adds a positive amount (refund or reversal). */
    @Transactional
    public StoreCreditTransaction credit(long phno, double amount, StoreCreditTransaction.Type type, Long orderId, String note) {
        double value = money(amount);
        if (value <= 0) {
            throw new IllegalArgumentException("credit amount must be positive");
        }
        StoreCreditAccount account = accounts.lockByPhno(phno).orElseGet(() -> newAccount(phno));
        return apply(account, value, type, orderId, note);
    }

    /** Admin correction: positive or negative, never taking the balance below zero. */
    @Transactional
    public StoreCreditSummary adjust(long phno, double amount, String note) {
        double value = money(amount);
        if (value == 0 || Math.abs(value) > MAX_ADJUSTMENT) {
            throw new ProductException("Amount must be non-zero and at most Rs. " + format(MAX_ADJUSTMENT) + " either way");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed == null) {
            throw new ProductException("A note explaining the adjustment is required");
        }
        if (trimmed.length() > 200) {
            throw new ProductException("Note must be at most 200 characters");
        }
        StoreCreditAccount account = accounts.lockByPhno(phno).orElseGet(() -> newAccount(phno));
        if (account.getBalance() + value < -0.0001) {
            throw new ProductException("That would take the balance below zero (it is Rs. " + format(account.getBalance()) + ")");
        }
        apply(account, value, StoreCreditTransaction.Type.ADJUSTED, null, trimmed);
        return summary(phno);
    }

    /** Fills in the order id on a checkout's SPENT row once the order has been saved and has one. */
    public void linkOrder(StoreCreditTransaction tx, long orderId) {
        if (tx != null) {
            tx.setOrderId(orderId);
            transactions.save(tx);
        }
    }

    private StoreCreditTransaction apply(StoreCreditAccount account, double delta, StoreCreditTransaction.Type type,
                                         Long orderId, String note) {
        double after = money(account.getBalance() + delta);
        account.setBalance(Math.max(0, after));
        account.setUpdatedAt(clock.instant());
        accounts.save(account);
        StoreCreditTransaction tx = new StoreCreditTransaction();
        tx.setPhno(account.getPhno());
        tx.setType(type);
        tx.setAmount(delta);
        tx.setBalanceAfter(account.getBalance());
        tx.setOrderId(orderId);
        tx.setNote(note);
        tx.setCreatedAt(clock.instant());
        return transactions.save(tx);
    }

    private StoreCreditAccount newAccount(long phno) {
        StoreCreditAccount account = new StoreCreditAccount();
        account.setPhno(phno);
        account.setBalance(0);
        return account;
    }

    static double money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
