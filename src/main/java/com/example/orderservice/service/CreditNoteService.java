package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.CreditNoteView;
import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CreditNote;
import com.example.orderservice.entity.CreditNoteLine;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.repository.CreditNoteRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues GST credit notes when units of an already-invoiced order are cancelled or returned, so the tax charged on the
 * invoice can be reversed on the books. The amounts are the invoice's tax before the change minus after it
 * (GstCalculator.reversal), using the same rate, price and place of supply the invoice itself uses, so an invoice
 * less its credit notes always equals the invoice as it is now. An order with no invoice number (never taken, e.g. a
 * UPI payment that never came through) has nothing to reverse and gets no note.
 */
@Service
public class CreditNoteService {
    /** Units of one order line standing before and after a cancel/return. */
    public record Change(OrderItem item, int keptBefore, int keptAfter) {
    }

    private record Facts(String name, String hsnCode, double rate, double unitPrice) {
    }

    private final CreditNoteRepository creditNotes;
    private final InvoiceNumberService numbers;
    private final ProductClient productClient;
    private final ShippingAddressRepository addresses;
    private final Clock clock;
    private final double defaultRate;
    private final String storeState;

    public CreditNoteService(CreditNoteRepository creditNotes, InvoiceNumberService numbers, ProductClient productClient,
                             ShippingAddressRepository addresses, Clock clock,
                             @Value("${gst.default-rate:18}") double defaultRate,
                             @Value("${gst.store-state:}") String storeState) {
        this.creditNotes = creditNotes;
        this.numbers = numbers;
        this.productClient = productClient;
        this.addresses = addresses;
        this.clock = clock;
        this.defaultRate = defaultRate;
        this.storeState = storeState == null ? "" : storeState.trim();
    }

    /** Issues one credit note covering the given changes, or nothing if there is no invoice or no value to reverse. */
    @Transactional
    public Optional<CreditNote> issue(Cart order, List<Change> changes, String reason) {
        if (order.getInvoiceNumber() == null || order.getOrderId() == null) {
            return Optional.empty();
        }
        Map<Integer, Facts> facts = new HashMap<>();
        List<GstCalculator.Item> items = new ArrayList<>();
        for (OrderItem item : order.getOrderItems()) {
            Facts f = facts.computeIfAbsent(item.getProductId(), id -> lookup(item));
            items.add(new GstCalculator.Item(item.getProductId(), f.name(), f.hsnCode(), f.rate(), f.unitPrice(),
                    item.getProductQuantity(), item.getOutstandingQuantity()));
        }
        double discountRatio = GstCalculator.discountRatio(items, order.getDiscountAmount());
        String placeOfSupply = placeOfSupply(order);
        boolean interState = GstCalculator.isInterState(storeState, placeOfSupply);

        CreditNote note = new CreditNote();
        for (Change change : changes) {
            if (change.keptBefore() <= change.keptAfter()) {
                continue;
            }
            GstCalculator.Item item = items.stream().filter(i -> i.productId() == change.item().getProductId()).findFirst().orElseThrow();
            Invoice.TaxLine reversal = GstCalculator.reversal(item, change.keptBefore(), change.keptAfter(), discountRatio, interState);
            if (reversal.total() <= 0) {
                continue;
            }
            CreditNoteLine line = new CreditNoteLine();
            line.setProductId(reversal.productId());
            line.setProductName(reversal.productName());
            line.setHsnCode(reversal.hsnCode());
            line.setGstRate(reversal.gstRate());
            line.setQuantity(reversal.quantity());
            line.setTaxableValue(reversal.taxableValue());
            line.setCgst(reversal.cgst());
            line.setSgst(reversal.sgst());
            line.setIgst(reversal.igst());
            line.setTotal(reversal.total());
            note.getLines().add(line);
            note.setTaxableValue(round(note.getTaxableValue() + line.getTaxableValue()));
            note.setCgst(round(note.getCgst() + line.getCgst()));
            note.setSgst(round(note.getSgst() + line.getSgst()));
            note.setIgst(round(note.getIgst() + line.getIgst()));
            note.setTotal(round(note.getTotal() + line.getTotal()));
        }
        if (note.getLines().isEmpty()) {
            return Optional.empty();
        }
        note.setNumber(numbers.nextCreditNoteNumber());
        note.setOrderId(order.getOrderId());
        note.setInvoiceNumber(order.getInvoiceNumber());
        note.setIssuedAt(clock.instant());
        note.setReason(reason);
        note.setInterState(interState);
        note.setPlaceOfSupply(placeOfSupply);
        return Optional.of(creditNotes.save(note));
    }

    @Transactional(readOnly = true)
    public List<CreditNoteView> forOrder(long orderId) {
        return creditNotes.findByOrderIdOrderByIdAsc(orderId).stream().map(CreditNoteView::of).toList();
    }

    // Same rate / price / HSN resolution as OrderService.getInvoice - keep the two in step. A product that has left the
    // catalog falls back to the order's own snapshot.
    private Facts lookup(OrderItem item) {
        String name = "Product #" + item.getProductId();
        double unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : 0;
        String hsnCode = null;
        Double catalogRate = null;
        try {
            Product p = productClient.getProductById(item.getProductId());
            if (p != null) {
                name = p.getProductName();
                hsnCode = p.getHsnCode();
                catalogRate = p.getGstRate();
                if (item.getUnitPrice() == null) {
                    unitPrice = p.getProductPrice();
                }
            }
        } catch (FeignException e) {
            // removed from the catalog - the fallback name/price is used
        }
        double rate = item.getGstRate() != null ? item.getGstRate() : catalogRate != null ? catalogRate : defaultRate;
        return new Facts(name, hsnCode, rate, unitPrice);
    }

    // Where the goods go; without a saved address it is taken to be the store's own state.
    private String placeOfSupply(Cart order) {
        ShippingAddress shipTo = order.getShippingAddressId() == null ? null
                : addresses.findById(order.getShippingAddressId()).orElse(null);
        return shipTo != null && shipTo.getState() != null && !shipTo.getState().isBlank()
                ? shipTo.getState().trim() : storeState;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
