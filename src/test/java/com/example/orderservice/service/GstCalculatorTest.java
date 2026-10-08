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
}
