package com.example.orderservice.service;

import com.example.orderservice.dto.Invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits GST out of an order's GST-inclusive prices for the tax invoice. Pure arithmetic, no I/O.
 *
 * Per line: the value actually supplied is unit price x quantity still kept (cancelled/returned units are not
 * supplied), less the line's share of any coupon discount (a discount given at the time of sale lowers the
 * taxable value). Loyalty points and store credit are ways of PAYING, not discounts, so they don't. Then
 * taxable = value / (1 + rate), tax = value - taxable, split evenly into CGST + SGST within the store's state, or
 * charged as IGST when the place of supply is another state. Each line is rounded to paise on its own and the
 * totals are sums of the rounded lines, so the printed figures always add up.
 */
public final class GstCalculator {
    private GstCalculator() {
    }

    public record Item(int productId, String productName, String hsnCode, double gstRate, double unitPrice,
                       int originalQuantity, int keptQuantity) {
    }

    public static Invoice.Tax compute(List<Item> items, double couponDiscount, boolean interState, String sellerName,
                                      String sellerGstin, String sellerState, String placeOfSupply) {
        double orderGross = items.stream().mapToDouble(i -> i.unitPrice() * i.originalQuantity()).sum();
        double discountRatio = orderGross <= 0 ? 0 : Math.min(1, Math.max(0, couponDiscount / orderGross));
        List<Invoice.TaxLine> lines = new ArrayList<>();
        double taxableTotal = 0, cgstTotal = 0, sgstTotal = 0, igstTotal = 0;
        for (Item item : items) {
            if (item.keptQuantity() <= 0) {
                continue;
            }
            double value = round(item.unitPrice() * item.keptQuantity() * (1 - discountRatio));
            double taxable = round(value / (1 + item.gstRate() / 100.0));
            double tax = round(value - taxable);
            double cgst = 0, sgst = 0, igst = 0;
            if (interState) {
                igst = tax;
            } else {
                cgst = round(tax / 2);
                sgst = round(tax - cgst);
            }
            lines.add(new Invoice.TaxLine(item.productId(), item.productName(), item.hsnCode(), item.gstRate(),
                    item.keptQuantity(), taxable, cgst, sgst, igst, value));
            taxableTotal += taxable;
            cgstTotal += cgst;
            sgstTotal += sgst;
            igstTotal += igst;
        }
        return new Invoice.Tax(sellerName, blankToNull(sellerGstin), blankToNull(sellerState), blankToNull(placeOfSupply),
                interState, lines, round(taxableTotal), round(cgstTotal), round(sgstTotal), round(igstTotal),
                round(cgstTotal + sgstTotal + igstTotal));
    }

    // Inter-state only when both states are known and differ; with either unknown the sale is treated as within
    // the store's own state.
    public static boolean isInterState(String sellerState, String placeOfSupply) {
        return sellerState != null && !sellerState.isBlank() && placeOfSupply != null && !placeOfSupply.isBlank()
                && !sellerState.trim().equalsIgnoreCase(placeOfSupply.trim());
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
