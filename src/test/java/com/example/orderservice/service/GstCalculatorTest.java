package com.example.orderservice.service;

import com.example.orderservice.dto.Invoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstCalculatorTest {

    private static GstCalculator.Item item(int id, double rate, double unitPrice, int qty, int kept) {
        return new GstCalculator.Item(id, "Item " + id, "3401", rate, unitPrice, qty, kept);
    }

    @Test
    void withinTheStoresStateTheTaxIsSplitIntoCgstAndSgst() {
        Invoice.Tax tax = GstCalculator.compute(List.of(item(1, 18, 118, 1, 1)), 0, false,
                "Charan Mart", "29ABCDE1234F1Z5", "Karnataka", "Karnataka");

        Invoice.TaxLine line = tax.lines().get(0);
        assertEquals(100.0, line.taxableValue());
        assertEquals(9.0, line.cgst());
        assertEquals(9.0, line.sgst());
        assertEquals(0.0, line.igst());
        assertEquals(118.0, line.total());
        assertEquals(18.0, tax.totalTax());
        assertEquals("29ABCDE1234F1Z5", tax.sellerGstin());
    }

    @Test
    void anotherStateGetsIgst() {
        Invoice.Tax tax = GstCalculator.compute(List.of(item(1, 5, 105, 2, 2)), 0, true,
                "Charan Mart", null, "Karnataka", "Maharashtra");

        assertEquals(200.0, tax.taxableValue());
        assertEquals(10.0, tax.igst());
        assertEquals(0.0, tax.cgst());
        assertTrue(tax.interState());
        assertEquals("Maharashtra", tax.placeOfSupply());
    }

    // Gross 200 (100 + 100), coupon 20 = 10% off every line; the second line's units were all cancelled.
    @Test
    void aCouponLowersTheTaxableValueAndCancelledUnitsAreLeftOut() {
        Invoice.Tax tax = GstCalculator.compute(List.of(item(1, 18, 100, 1, 1), item(2, 12, 100, 1, 0)), 20, false,
                "Charan Mart", "", "", null);

        assertEquals(1, tax.lines().size());
        Invoice.TaxLine line = tax.lines().get(0);
        assertEquals(90.0, line.total());
        assertEquals(76.27, line.taxableValue());
        assertEquals(13.73, line.cgst() + line.sgst(), 0.0001);
        assertEquals(6.87, line.cgst());
        assertEquals(6.86, line.sgst());
        assertNull(tax.sellerGstin());   // blank config is not printed
        assertNull(tax.sellerState());
    }

    @Test
    void totalsAreTheSumOfTheRoundedLinesAndZeroRatedGoodsCarryNoTax() {
        Invoice.Tax tax = GstCalculator.compute(List.of(item(1, 18, 33.33, 3, 3), item(2, 0, 50, 1, 1)), 0, false,
                "Charan Mart", null, null, null);

        double lineTaxable = tax.lines().stream().mapToDouble(Invoice.TaxLine::taxableValue).sum();
        double lineTax = tax.lines().stream().mapToDouble(l -> l.cgst() + l.sgst()).sum();
        assertEquals(lineTaxable, tax.taxableValue(), 0.0001);
        assertEquals(lineTax, tax.totalTax(), 0.0001);
        assertEquals(149.99, tax.taxableValue() + tax.totalTax(), 0.0001);
        assertEquals(50.0, tax.lines().get(1).taxableValue());
        assertEquals(0.0, tax.lines().get(1).cgst());
    }

    @Test
    void interStateOnlyWhenBothStatesAreKnownAndDiffer() {
        assertTrue(GstCalculator.isInterState("Karnataka", "Maharashtra"));
        assertFalse(GstCalculator.isInterState("Karnataka", " karnataka "));
        assertFalse(GstCalculator.isInterState("", "Maharashtra"));
        assertFalse(GstCalculator.isInterState("Karnataka", null));
    }

    // The reversals of a sequence of cancels, plus the tax still standing, always add back up to the original invoice
    // to the paisa - even where per-line rounding would otherwise drift (odd price, coupon share, 4 units).
    @Test
    void successiveReversalsAddUpToTheOriginalInvoiceToThePaisa() {
        GstCalculator.Item it = item(1, 5, 9.99, 4, 4);
        double ratio = 0.0731;
        for (boolean interState : new boolean[]{false, true}) {
            Invoice.TaxLine whole = GstCalculator.lineTax(it, 4, ratio, interState);
            double taxable = 0, cgst = 0, sgst = 0, igst = 0, total = 0;
            int kept = 4;
            for (int cancel : new int[]{1, 1, 2}) {
                Invoice.TaxLine r = GstCalculator.reversal(it, kept, kept - cancel, ratio, interState);
                assertEquals(cancel, r.quantity());
                taxable += r.taxableValue();
                cgst += r.cgst();
                sgst += r.sgst();
                igst += r.igst();
                total += r.total();
                kept -= cancel;
            }
            assertEquals(whole.taxableValue(), Math.round(taxable * 100) / 100.0);
            assertEquals(whole.cgst(), Math.round(cgst * 100) / 100.0);
            assertEquals(whole.sgst(), Math.round(sgst * 100) / 100.0);
            assertEquals(whole.igst(), Math.round(igst * 100) / 100.0);
            assertEquals(whole.total(), Math.round(total * 100) / 100.0);
        }
    }

    @Test
    void reversingOneUnitReturnsThatUnitsShareOfTaxAfterTheCouponShare() {
        // 2 x 118 at 18%, a coupon took 10% off the order: one unit supplied for 106.20 -> 90 taxable + 16.20 tax.
        GstCalculator.Item it = item(1, 18, 118, 2, 2);
        double ratio = GstCalculator.discountRatio(List.of(it), 23.6);

        Invoice.TaxLine r = GstCalculator.reversal(it, 2, 1, ratio, false);

        assertEquals(106.2, r.total());
        assertEquals(90.0, r.taxableValue());
        assertEquals(8.1, r.cgst());
        assertEquals(8.1, r.sgst());
    }
}
