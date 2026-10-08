package com.example.orderservice.service;

import com.example.orderservice.dto.CreditNoteView;
import com.example.orderservice.dto.Invoice;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders an {@link Invoice} (with its GST breakdown and credit notes) as an A4 PDF: the same figures as the printable
 * HTML invoice and the invoice email, in a file a customer can keep and an accountant can file. Amounts are written
 * "Rs." because the built-in PDF fonts have no rupee sign; text outside Latin-1 (which those fonts can't draw either)
 * is shown as "?".
 */
@Service
public class InvoicePdfService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color HEADER_BG = new Color(0xEE, 0xEE, 0xEE);

    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font HEADING = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 9);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, GREY);

    private final ZoneId zone;
    private final String storeName;

    public InvoicePdfService(@Value("${digest.zone:Asia/Kolkata}") String zone,
                             @Value("${gst.store-name:Charan Mart}") String storeName) {
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
        this.storeName = storeName == null || storeName.isBlank() ? "Charan Mart" : storeName.trim();
    }

    public String fileName(Invoice inv) {
        String base = inv.invoiceNumber() != null ? inv.invoiceNumber() : "order-" + inv.orderId();
        return "invoice-" + base.replaceAll("[^A-Za-z0-9._-]", "-") + ".pdf";
    }

    public byte[] render(Invoice inv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            header(doc, inv);
            parties(doc, inv);
            items(doc, inv);
            totals(doc, inv);
            gst(doc, inv.tax());
            creditNotes(doc, inv.creditNotes());
            Paragraph foot = new Paragraph("Prices include GST. On older orders the unit prices show the catalog price at the time "
                    + "the invoice was produced; the total is what the order was actually charged.", SMALL);
            foot.setSpacingBefore(14);
            doc.add(foot);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the invoice PDF", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private void header(Document doc, Invoice inv) throws DocumentException {
        Invoice.Tax tax = inv.tax();
        PdfPTable t = table(new float[]{3, 2});
        PdfPCell seller = cell();
        seller.addElement(new Paragraph(safe(tax != null && tax.sellerName() != null ? tax.sellerName() : storeName), TITLE));
        if (tax != null && tax.sellerGstin() != null) {
            seller.addElement(new Paragraph("GSTIN " + safe(tax.sellerGstin()), BODY));
        }
        if (tax != null && tax.sellerState() != null) {
            seller.addElement(new Paragraph("State: " + safe(tax.sellerState()), BODY));
        }
        t.addCell(seller);

        PdfPCell meta = cell();
        meta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        meta.addElement(right(new Paragraph(inv.invoiceNumber() != null ? "TAX INVOICE" : "INVOICE", HEADING)));
        if (inv.invoiceNumber() != null) {
            meta.addElement(right(new Paragraph("No. " + safe(inv.invoiceNumber()), BOLD)));
            if (inv.invoiceDate() != null) {
                meta.addElement(right(new Paragraph("Date: " + DATE.format(inv.invoiceDate().atZone(zone)), BODY)));
            }
        }
        meta.addElement(right(new Paragraph("Order #" + inv.orderId(), BODY)));
        if (inv.placedAt() != null) {
            meta.addElement(right(new Paragraph("Placed: " + DATE_TIME.format(inv.placedAt().atZone(zone)), BODY)));
        }
        t.addCell(meta);
        doc.add(t);
    }

    private void parties(Document doc, Invoice inv) throws DocumentException {
        PdfPTable t = table(new float[]{1, 1});
        t.setSpacingBefore(14);
        PdfPCell bill = cell();
        bill.addElement(new Paragraph("Billed to", SMALL));
        bill.addElement(new Paragraph(safe(inv.customerName() == null || inv.customerName().isBlank() ? "Customer" : inv.customerName()), BOLD));
        bill.addElement(new Paragraph("Phone " + inv.customerPhno(), BODY));
        t.addCell(bill);

        PdfPCell ship = cell();
        ship.addElement(new Paragraph("Ship to", SMALL));
        ship.addElement(new Paragraph(safe(inv.shippingAddress() == null ? "-" : inv.shippingAddress()), BODY));
        if (inv.tax() != null && inv.tax().placeOfSupply() != null) {
            ship.addElement(new Paragraph("Place of supply: " + safe(inv.tax().placeOfSupply()), BODY));
        }
        if (inv.deliverySlot() != null && !inv.deliverySlot().isBlank()) {
            ship.addElement(new Paragraph("Delivery slot: " + safe(inv.deliverySlot()), BODY));
        }
        t.addCell(ship);
        doc.add(t);

        Paragraph pay = new Paragraph("Payment: " + safe(inv.paymentMethod()) + (inv.paid() ? " (paid)" : " (unpaid)")
                + "   |   Status: " + safe(inv.status()), BODY);
        pay.setSpacingBefore(6);
        doc.add(pay);
    }

    private void items(Document doc, Invoice inv) throws DocumentException {
        PdfPTable t = table(new float[]{5, 1, 2, 2});
        t.setSpacingBefore(14);
        head(t, "Item", Element.ALIGN_LEFT);
        head(t, "Qty", Element.ALIGN_RIGHT);
        head(t, "Unit price", Element.ALIGN_RIGHT);
        head(t, "Amount", Element.ALIGN_RIGHT);
        for (Invoice.Line line : inv.lines()) {
            StringBuilder name = new StringBuilder(safe(line.productName()));
            if (line.cancelledQuantity() > 0) {
                name.append(" (").append(line.cancelledQuantity()).append(" cancelled)");
            }
            if (line.returnedQuantity() > 0) {
                name.append(" (").append(line.returnedQuantity()).append(" returned)");
            }
            body(t, name.toString(), Element.ALIGN_LEFT);
            body(t, String.valueOf(line.quantity()), Element.ALIGN_RIGHT);
            body(t, money(line.unitPrice()), Element.ALIGN_RIGHT);
            body(t, money(line.lineTotal()), Element.ALIGN_RIGHT);
        }
        doc.add(t);
    }

    private void totals(Document doc, Invoice inv) throws DocumentException {
        PdfPTable t = table(new float[]{5, 2});
        t.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.setWidthPercentage(55);
        t.setSpacingBefore(8);
        if (inv.discountAmount() > 0) {
            total(t, "Coupon" + (inv.couponCode() == null ? "" : " (" + safe(inv.couponCode()) + ")"), "-" + money(inv.discountAmount()), false);
        }
        if (inv.pointsRedeemed() > 0) {
            total(t, "Loyalty points redeemed", "-" + money(inv.pointsRedeemed()), false);
        }
        if (inv.storeCreditUsed() > 0) {
            total(t, "Store credit used", "-" + money(inv.storeCreditUsed()), false);
        }
        total(t, "Total charged", money(inv.totalPrice()), true);
        if (inv.refundedAmount() > 0) {
            total(t, "Refunded or taken off since", money(inv.refundedAmount()), false);
        }
        doc.add(t);
    }

    private void gst(Document doc, Invoice.Tax tax) throws DocumentException {
        if (tax == null || tax.lines().isEmpty()) {
            return;
        }
        Paragraph h = new Paragraph("GST breakdown (already included in the prices above)", HEADING);
        h.setSpacingBefore(16);
        h.setSpacingAfter(4);
        doc.add(h);
        PdfPTable t = table(new float[]{4, 2, 1.3f, 2, 1.8f, 1.8f, 1.8f, 2});
        head(t, "Item", Element.ALIGN_LEFT);
        head(t, "HSN", Element.ALIGN_LEFT);
        head(t, "Rate", Element.ALIGN_RIGHT);
        head(t, "Taxable", Element.ALIGN_RIGHT);
        head(t, "CGST", Element.ALIGN_RIGHT);
        head(t, "SGST", Element.ALIGN_RIGHT);
        head(t, "IGST", Element.ALIGN_RIGHT);
        head(t, "Total", Element.ALIGN_RIGHT);
        for (Invoice.TaxLine l : tax.lines()) {
            body(t, safe(l.productName()) + " x" + l.quantity(), Element.ALIGN_LEFT);
            body(t, safe(l.hsnCode() == null ? "-" : l.hsnCode()), Element.ALIGN_LEFT);
            body(t, percent(l.gstRate()), Element.ALIGN_RIGHT);
            body(t, money(l.taxableValue()), Element.ALIGN_RIGHT);
            body(t, money(l.cgst()), Element.ALIGN_RIGHT);
            body(t, money(l.sgst()), Element.ALIGN_RIGHT);
            body(t, money(l.igst()), Element.ALIGN_RIGHT);
            body(t, money(l.total()), Element.ALIGN_RIGHT);
        }
        bold(t, "Total", Element.ALIGN_LEFT);
        bold(t, "", Element.ALIGN_LEFT);
        bold(t, "", Element.ALIGN_RIGHT);
        bold(t, money(tax.taxableValue()), Element.ALIGN_RIGHT);
        bold(t, money(tax.cgst()), Element.ALIGN_RIGHT);
        bold(t, money(tax.sgst()), Element.ALIGN_RIGHT);
        bold(t, money(tax.igst()), Element.ALIGN_RIGHT);
        bold(t, money(tax.totalTax()), Element.ALIGN_RIGHT);
        doc.add(t);
        Paragraph note = new Paragraph(tax.interState()
                ? "Supplied to another state: IGST applies." : "Supplied within the seller's state: CGST + SGST apply.", SMALL);
        note.setSpacingBefore(3);
        doc.add(note);
    }

    private void creditNotes(Document doc, List<CreditNoteView> notes) throws DocumentException {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        Paragraph h = new Paragraph("Credit notes (tax reversed for cancelled or returned items)", HEADING);
        h.setSpacingBefore(16);
        h.setSpacingAfter(4);
        doc.add(h);
        for (CreditNoteView n : notes) {
            Paragraph title = new Paragraph(safe(n.number()) + "  -  " + ("RETURNED".equals(n.reason()) ? "Returned" : "Cancelled")
                    + "  -  " + date(n.issuedAt()) + "  -  against " + safe(n.invoiceNumber() == null ? "invoice" : n.invoiceNumber()), BOLD);
            title.setSpacingBefore(6);
            title.setSpacingAfter(2);
            doc.add(title);
            PdfPTable t = table(new float[]{4, 2, 1.3f, 2, 1.8f, 1.8f, 1.8f, 2});
            head(t, "Item", Element.ALIGN_LEFT);
            head(t, "HSN", Element.ALIGN_LEFT);
            head(t, "Rate", Element.ALIGN_RIGHT);
            head(t, "Taxable", Element.ALIGN_RIGHT);
            head(t, "CGST", Element.ALIGN_RIGHT);
            head(t, "SGST", Element.ALIGN_RIGHT);
            head(t, "IGST", Element.ALIGN_RIGHT);
            head(t, "Total", Element.ALIGN_RIGHT);
            for (CreditNoteView.Line l : n.lines()) {
                body(t, safe(l.productName()) + " x" + l.quantity(), Element.ALIGN_LEFT);
                body(t, safe(l.hsnCode() == null ? "-" : l.hsnCode()), Element.ALIGN_LEFT);
                body(t, percent(l.gstRate()), Element.ALIGN_RIGHT);
                body(t, money(l.taxableValue()), Element.ALIGN_RIGHT);
                body(t, money(l.cgst()), Element.ALIGN_RIGHT);
                body(t, money(l.sgst()), Element.ALIGN_RIGHT);
                body(t, money(l.igst()), Element.ALIGN_RIGHT);
                body(t, money(l.total()), Element.ALIGN_RIGHT);
            }
            bold(t, "Credit note total", Element.ALIGN_LEFT);
            bold(t, "", Element.ALIGN_LEFT);
            bold(t, "", Element.ALIGN_RIGHT);
            bold(t, money(n.taxableValue()), Element.ALIGN_RIGHT);
            bold(t, money(n.cgst()), Element.ALIGN_RIGHT);
            bold(t, money(n.sgst()), Element.ALIGN_RIGHT);
            bold(t, money(n.igst()), Element.ALIGN_RIGHT);
            bold(t, money(n.total()), Element.ALIGN_RIGHT);
            doc.add(t);
        }
    }

    // ---- small layout helpers ----

    private static PdfPTable table(float[] widths) throws DocumentException {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static Paragraph right(Paragraph p) {
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static void head(PdfPTable t, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, BOLD));
        c.setBackgroundColor(HEADER_BG);
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        t.addCell(c);
    }

    private static void body(PdfPTable t, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(HEADER_BG);
        t.addCell(c);
    }

    private static void bold(PdfPTable t, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, BOLD));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        c.setBorder(Rectangle.TOP);
        t.addCell(c);
    }

    private static void total(PdfPTable t, String label, String value, boolean strong) {
        Font f = strong ? BOLD : BODY;
        PdfPCell l = new PdfPCell(new Phrase(label, f));
        l.setBorder(strong ? Rectangle.TOP : Rectangle.NO_BORDER);
        l.setPadding(3);
        t.addCell(l);
        PdfPCell v = new PdfPCell(new Phrase(value, f));
        v.setBorder(strong ? Rectangle.TOP : Rectangle.NO_BORDER);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setPadding(3);
        t.addCell(v);
    }

    private String date(Instant at) {
        return at == null ? "" : DATE.format(at.atZone(zone));
    }

    private static String money(double value) {
        return "Rs. " + String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double rate) {
        return rate == Math.rint(rate) ? String.format(Locale.ROOT, "%.0f%%", rate) : String.format(Locale.ROOT, "%.1f%%", rate);
    }

    // The built-in PDF fonts cover Latin-1 only; anything else would come out as an empty box or fail.
    static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> b.append(cp < 256 && (cp >= 32 || cp == '\n') ? (char) cp : '?'));
        return b.toString();
    }
}
