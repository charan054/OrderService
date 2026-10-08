package com.example.orderservice.dto;

import com.example.orderservice.entity.CreditNote;

import java.time.Instant;
import java.util.List;

// A credit note as shown on the invoice (the tax reversed by a cancel/return after the invoice was issued).
public record CreditNoteView(String number, Instant issuedAt, String reason, String invoiceNumber, boolean interState,
                             double taxableValue, double cgst, double sgst, double igst, double total,
                             List<Line> lines) {
    public record Line(int productId, String productName, String hsnCode, double gstRate, int quantity,
                       double taxableValue, double cgst, double sgst, double igst, double total) {
    }

    public static CreditNoteView of(CreditNote n) {
        return new CreditNoteView(n.getNumber(), n.getIssuedAt(), n.getReason(), n.getInvoiceNumber(), n.isInterState(),
                n.getTaxableValue(), n.getCgst(), n.getSgst(), n.getIgst(), n.getTotal(),
                n.getLines().stream().map(l -> new Line(l.getProductId(), l.getProductName(), l.getHsnCode(),
                        l.getGstRate(), l.getQuantity(), l.getTaxableValue(), l.getCgst(), l.getSgst(), l.getIgst(),
                        l.getTotal())).toList());
    }
}
