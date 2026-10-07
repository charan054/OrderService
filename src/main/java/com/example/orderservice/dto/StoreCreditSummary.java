package com.example.orderservice.dto;

import com.example.orderservice.entity.StoreCreditTransaction;

import java.util.List;

// GET /storecredit/byphno: the balance and the 50 most recent changes, newest first.
public record StoreCreditSummary(long phno, double balance, List<StoreCreditTransaction> transactions) {
}
