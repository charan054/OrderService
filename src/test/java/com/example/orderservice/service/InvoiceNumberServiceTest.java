package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.InvoiceSequence;
import com.example.orderservice.repository.InvoiceSequenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceNumberServiceTest {
    @Mock
    private InvoiceSequenceRepository sequences;

    private InvoiceNumberService at(String instant) {
        return new InvoiceNumberService(sequences, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), "CM", "Asia/Kolkata");
    }

    @Test
    void theFinancialYearRunsAprilToMarch() {
        assertEquals("2026-27", InvoiceNumberService.fiscalYear(LocalDate.of(2026, 4, 1)));
        assertEquals("2026-27", InvoiceNumberService.fiscalYear(LocalDate.of(2027, 3, 31)));
        assertEquals("2025-26", InvoiceNumberService.fiscalYear(LocalDate.of(2026, 3, 31)));
        assertEquals("2099-00", InvoiceNumberService.fiscalYear(LocalDate.of(2099, 12, 1)));
    }

    @Test
    void numbersFollowOnFromTheLastOneIssued() {
        InvoiceSequence seq = new InvoiceSequence();
        seq.setFiscalYear("2026-27");
        seq.setLastNumber(41);
        when(sequences.lockByFiscalYear("2026-27")).thenReturn(Optional.of(seq));
        Cart order = new Cart();

        at("2026-10-08T10:00:00Z").assign(order);

        assertEquals("CM/2026-27/000042", order.getInvoiceNumber());
        assertEquals(Instant.parse("2026-10-08T10:00:00Z"), order.getInvoiceDate());
        assertEquals(42, seq.getLastNumber());
        verify(sequences).save(seq);
    }

    // 31 Mar 2027 20:00 UTC is already 1 Apr 2027 in India - a new financial year, starting again at 1.
    @Test
    void aNewFinancialYearStartsAtOneUsingIndianTime() {
        when(sequences.lockByFiscalYear("2027-28")).thenReturn(Optional.empty());
        Cart order = new Cart();

        at("2027-03-31T20:00:00Z").assign(order);

        assertEquals("CM/2027-28/000001", order.getInvoiceNumber());
        ArgumentCaptor<InvoiceSequence> saved = ArgumentCaptor.forClass(InvoiceSequence.class);
        verify(sequences).save(saved.capture());
        assertEquals("2027-28", saved.getValue().getFiscalYear());
    }

    @Test
    void anOrderKeepsTheNumberItAlreadyHas() {
        Cart order = new Cart();
        order.setInvoiceNumber("CM/2026-27/000007");

        at("2026-10-08T10:00:00Z").assign(order);

        assertEquals("CM/2026-27/000007", order.getInvoiceNumber());
        verify(sequences, never()).save(any());
    }
}
