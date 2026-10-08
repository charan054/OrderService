package com.example.orderservice.service;

import com.example.orderservice.dto.CreditNoteView;
import com.example.orderservice.dto.Invoice;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoicePdfServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private final InvoicePdfService service = new InvoicePdfService("UTC", "Charan Mart");

    private Invoice invoice(Invoice.Tax tax, List<CreditNoteView> notes, String invoiceNumber, String name) {
        return new Invoice(7, NOW, name, 9876543210L,
                List.of(new Invoice.Line(1, "Soap", 2, 30.0, 60.0, 1, 0)),
                "WELCOME10", 6.0, 10, 0.0, 44.0, 20.0, "CASH", false, "DELIVERED", "1 Main St, Pune, MH, 411001", null, "Morning",
                invoiceNumber, invoiceNumber == null ? null : NOW, tax, notes);
    }

    private String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder all = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            all.append(extractor.getTextFromPage(page)).append('\n');
        }
        reader.close();
        return all.toString();
    }

    @Test
    void aFullTaxInvoiceShowsTheNumberTheTotalsTheGstTableAndTheCreditNotes() throws IOException {
        Invoice.Tax tax = new Invoice.Tax("Charan Mart", "29ABCDE1234F1Z5", "Karnataka", "Karnataka", false,
                List.of(new Invoice.TaxLine(1, "Soap", "3401", 18, 1, 25.42, 2.29, 2.29, 0, 30)),
                25.42, 2.29, 2.29, 0, 4.58);
        CreditNoteView note = new CreditNoteView("CN/2026-27/000003", NOW, "CANCELLED", "CM/2026-27/000042", false,
                25.42, 2.29, 2.29, 0, 30,
                List.of(new CreditNoteView.Line(1, "Soap", "3401", 18, 1, 25.42, 2.29, 2.29, 0, 30)));

        byte[] pdf = service.render(invoice(tax, List.of(note), "CM/2026-27/000042", "Asha"));

        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String text = text(pdf);
        assertTrue(text.contains("TAX INVOICE"));
        assertTrue(text.contains("CM/2026-27/000042"));
        assertTrue(text.contains("29ABCDE1234F1Z5"));
        assertTrue(text.contains("Asha"));
        assertTrue(text.contains("Soap (1 cancelled)"));
        assertTrue(text.contains("Rs. 44.00"));
        assertTrue(text.contains("WELCOME10"));
        assertTrue(text.contains("3401"));
        assertTrue(text.contains("CGST"));
        assertTrue(text.contains("CN/2026-27/000003"));
    }

    @Test
    void anOrderWithoutTaxDataStillRendersAsAPlainInvoice() throws IOException {
        byte[] pdf = service.render(invoice(null, List.of(), null, "Asha"));

        String text = text(pdf);
        assertTrue(text.contains("INVOICE"));
        assertFalse(text.contains("TAX INVOICE"));
        assertTrue(text.contains("Order #7"));
        assertFalse(text.contains("GST breakdown"));
    }

    @Test
    void textOutsideLatinOneBecomesAQuestionMarkInsteadOfBreakingThePdf() throws IOException {
        byte[] pdf = service.render(invoice(null, List.of(), null, "आशा"));

        assertTrue(text(pdf).contains("???"));
    }

    @Test
    void theFileNameUsesTheInvoiceNumberOrFallsBackToTheOrderId() {
        assertEquals("invoice-CM-2026-27-000042.pdf", service.fileName(invoice(null, List.of(), "CM/2026-27/000042", "A")));
        assertEquals("invoice-order-7.pdf", service.fileName(invoice(null, List.of(), null, "A")));
    }
}
