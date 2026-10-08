package com.example.orderservice.service;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * The GST report (see {@link GstReportService}) as a landscape A4 PDF an accountant can file: the same rows and totals as
 * the JSON and CSV versions, with the store's name and GSTIN at the top. Amounts are written "Rs." because the built-in
 * PDF fonts have no rupee sign.
 */
@Service
public class GstReportPdfService {
    private static final Color HEADER_BG = new Color(0xEE, 0xEE, 0xEE);
    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font HEADING = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 8);
    private static final Font BOLD = new Font(Font.HELVETICA, 8, Font.BOLD);

    private final String storeName;
    private final String storeGstin;
    private final String storeState;

    public GstReportPdfService(@Value("${gst.store-name:Charan Mart}") String storeName,
                               @Value("${gst.store-gstin:}") String storeGstin,
                               @Value("${gst.store-state:}") String storeState) {
        this.storeName = storeName == null || storeName.isBlank() ? "Charan Mart" : storeName.trim();
        this.storeGstin = storeGstin == null ? "" : storeGstin.trim();
        this.storeState = storeState == null ? "" : storeState.trim();
    }

    public byte[] render(GstReportService.Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("GST report - " + InvoicePdfService.safe(storeName), TITLE));
            StringBuilder who = new StringBuilder();
            if (!storeGstin.isEmpty()) {
                who.append("GSTIN ").append(InvoicePdfService.safe(storeGstin)).append("   ");
            }
            if (!storeState.isEmpty()) {
                who.append("State: ").append(InvoicePdfService.safe(storeState)).append("   ");
            }
            who.append("Period: ").append(report.from()).append(" to ").append(report.to());
            Paragraph meta = new Paragraph(who.toString(), BODY);
            meta.setSpacingAfter(8);
            doc.add(meta);

            Paragraph summary = new Paragraph(report.invoices() + " tax invoices, " + report.creditNotes()
                    + " credit notes. Invoices are counted at the quantities they were issued for; what was cancelled or "
                    + "returned later is in the credit notes.", BODY);
            summary.setSpacingAfter(8);
            doc.add(summary);

            PdfPTable t = new PdfPTable(new float[]{1.6f, 1.6f, 2.4f, 1, 1, 2, 1.8f, 1.8f, 1.8f, 1.8f, 2});
            t.setWidthPercentage(100);
            head(t, "Month", Element.ALIGN_LEFT);
            head(t, "Type", Element.ALIGN_LEFT);
            head(t, "Place of supply", Element.ALIGN_LEFT);
            head(t, "GST %", Element.ALIGN_RIGHT);
            head(t, "Docs", Element.ALIGN_RIGHT);
            head(t, "Taxable", Element.ALIGN_RIGHT);
            head(t, "CGST", Element.ALIGN_RIGHT);
            head(t, "SGST", Element.ALIGN_RIGHT);
            head(t, "IGST", Element.ALIGN_RIGHT);
            head(t, "Total tax", Element.ALIGN_RIGHT);
            head(t, "Value", Element.ALIGN_RIGHT);
            for (GstReportService.Row r : report.rows()) {
                cell(t, r.month(), Element.ALIGN_LEFT, BODY, false);
                cell(t, GstReportService.CREDIT_NOTE.equals(r.type()) ? "Credit note" : "Invoice", Element.ALIGN_LEFT, BODY, false);
                cell(t, InvoicePdfService.safe(r.placeOfSupply() == null || r.placeOfSupply().isBlank() ? "-" : r.placeOfSupply()), Element.ALIGN_LEFT, BODY, false);
                cell(t, rate(r.gstRate()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, String.valueOf(r.documents()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.taxableValue()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.cgst()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.sgst()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.igst()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.totalTax()), Element.ALIGN_RIGHT, BODY, false);
                cell(t, money(r.value()), Element.ALIGN_RIGHT, BODY, false);
            }
            totals(t, "Invoices", report.invoiceTotals());
            totals(t, "Credit notes", report.creditNoteTotals());
            totals(t, "Net", report.net());
            doc.add(t);
            if (report.rows().isEmpty()) {
                Paragraph none = new Paragraph("No invoices or credit notes in this period.", HEADING);
                none.setSpacingBefore(10);
                doc.add(none);
            }
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the GST report PDF", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static void totals(PdfPTable t, String label, GstReportService.Totals totals) {
        cell(t, label, Element.ALIGN_LEFT, BOLD, true);
        cell(t, "", Element.ALIGN_LEFT, BOLD, true);
        cell(t, "", Element.ALIGN_LEFT, BOLD, true);
        cell(t, "", Element.ALIGN_RIGHT, BOLD, true);
        cell(t, "", Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.taxableValue()), Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.cgst()), Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.sgst()), Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.igst()), Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.totalTax()), Element.ALIGN_RIGHT, BOLD, true);
        cell(t, money(totals.value()), Element.ALIGN_RIGHT, BOLD, true);
    }

    private static void head(PdfPTable t, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, BOLD));
        c.setBackgroundColor(HEADER_BG);
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        t.addCell(c);
    }

    private static void cell(PdfPTable t, String text, int align, Font font, boolean topBorder) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        c.setBorder(topBorder ? Rectangle.TOP : Rectangle.BOTTOM);
        if (!topBorder) {
            c.setBorderColor(HEADER_BG);
        }
        t.addCell(c);
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String rate(double rate) {
        return rate == Math.rint(rate) ? String.format(Locale.ROOT, "%.0f", rate) : String.format(Locale.ROOT, "%.2f", rate);
    }
}
