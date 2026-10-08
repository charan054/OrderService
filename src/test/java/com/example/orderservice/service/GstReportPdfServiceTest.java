package com.example.orderservice.service;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstReportPdfServiceTest {
    private final GstReportPdfService service = new GstReportPdfService("Charan Mart", "29ABCDE1234F1Z5", "Karnataka");

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        String all = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        return all;
    }

    @Test
    void theReportShowsTheStoreThePeriodTheRowsAndTheTotals() throws IOException {
        var totals = new GstReportService.Totals(100, 9, 9, 0, 18, 118);
        var credit = new GstReportService.Totals(10, 0.9, 0.9, 0, 1.8, 11.8);
        var net = new GstReportService.Totals(90, 8.1, 8.1, 0, 16.2, 106.2);
        var report = new GstReportService.Report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 3, 1,
                List.of(new GstReportService.Row("2026-10", GstReportService.INVOICE, "Karnataka", 18, 3, 100, 9, 9, 0, 18, 118),
                        new GstReportService.Row("2026-10", GstReportService.CREDIT_NOTE, "Karnataka", 18, 1, 10, 0.9, 0.9, 0, 1.8, 11.8)),
                totals, credit, net);

        byte[] pdf = service.render(report);

        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String text = text(pdf);
        assertTrue(text.contains("GST report - Charan Mart"));
        assertTrue(text.contains("29ABCDE1234F1Z5"));
        assertTrue(text.contains("2026-10-01 to 2026-10-31"));
        assertTrue(text.contains("3 tax invoices, 1 credit notes"));
        assertTrue(text.contains("Credit note"));
        assertTrue(text.contains("106.20"));
    }

    @Test
    void anEmptyPeriodStillRendersAndSaysSo() throws IOException {
        var zero = new GstReportService.Totals(0, 0, 0, 0, 0, 0);
        var report = new GstReportService.Report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 0, 0, List.of(), zero, zero, zero);

        assertTrue(text(service.render(report)).contains("No invoices or credit notes in this period."));
    }
}
