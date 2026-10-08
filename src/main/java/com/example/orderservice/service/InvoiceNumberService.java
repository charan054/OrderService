package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CreditNoteSequence;
import com.example.orderservice.entity.InvoiceSequence;
import com.example.orderservice.repository.CreditNoteSequenceRepository;
import com.example.orderservice.repository.InvoiceSequenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Sequential tax-invoice numbers, restarting each Indian financial year (April-March): PREFIX/2026-27/000001,
 * PREFIX/2026-27/000002, ... An order gets its number when it is placed (a UPI order when its payment is approved);
 * an order from before invoice numbers existed gets one the first time its invoice is opened. The counter row is
 * locked while it is advanced, so two orders can never share a number; a number is never reused, though a checkout
 * that fails after taking one leaves a gap.
 */
@Service
public class InvoiceNumberService {
    private final InvoiceSequenceRepository sequences;
    private final CreditNoteSequenceRepository creditNoteSequences;
    private final Clock clock;
    private final String prefix;
    private final ZoneId zone;

    public InvoiceNumberService(InvoiceSequenceRepository sequences, CreditNoteSequenceRepository creditNoteSequences, Clock clock,
                                @Value("${gst.invoice-prefix:CM}") String prefix,
                                @Value("${digest.zone:Asia/Kolkata}") String zone) {
        this.sequences = sequences;
        this.creditNoteSequences = creditNoteSequences;
        this.clock = clock;
        this.prefix = prefix == null || prefix.isBlank() ? "CM" : prefix.trim();
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
    }

    /** Gives the order an invoice number and date if it doesn't have them yet; the caller saves the order. */
    @Transactional
    public synchronized void assign(Cart order) {
        if (order.getInvoiceNumber() != null) {
            return;
        }
        Instant now = clock.instant();
        String fiscalYear = fiscalYear(LocalDate.ofInstant(now, zone));
        InvoiceSequence sequence = sequences.lockByFiscalYear(fiscalYear).orElseGet(() -> {
            InvoiceSequence fresh = new InvoiceSequence();
            fresh.setFiscalYear(fiscalYear);
            fresh.setLastNumber(0);
            return fresh;
        });
        sequence.setLastNumber(sequence.getLastNumber() + 1);
        sequences.save(sequence);
        order.setInvoiceNumber(String.format("%s/%s/%06d", prefix, fiscalYear, sequence.getLastNumber()));
        order.setInvoiceDate(now);
    }

    /** The next credit note number, PREFIX/CN/2026-27/000001, in its own series per financial year. */
    @Transactional
    public synchronized String nextCreditNoteNumber() {
        String fiscalYear = fiscalYear(LocalDate.ofInstant(clock.instant(), zone));
        CreditNoteSequence sequence = creditNoteSequences.lockByFiscalYear(fiscalYear).orElseGet(() -> {
            CreditNoteSequence fresh = new CreditNoteSequence();
            fresh.setFiscalYear(fiscalYear);
            fresh.setLastNumber(0);
            return fresh;
        });
        sequence.setLastNumber(sequence.getLastNumber() + 1);
        creditNoteSequences.save(sequence);
        return String.format("%s/CN/%s/%06d", prefix, fiscalYear, sequence.getLastNumber());
    }

    // 1 Apr 2026 - 31 Mar 2027 -> "2026-27".
    static String fiscalYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
