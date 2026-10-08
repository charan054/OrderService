package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CreditNote;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CreditNoteRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tax a month's books need: outward supplies (tax invoices) and the credit notes that reverse some of them, each
 * grouped by month, place of supply (state) and GST rate - the shape GSTR-1's B2C summary asks for - with the net
 * of the two. Invoices are counted at the quantities they were issued for; what was cancelled or returned later is
 * the credit notes' job. Months and the date range are in the store's time zone (digest.zone).
 *
 * An invoice's tax uses the rate recorded on each order line when it was sold (the store default for lines that
 * pre-date that), so figures match the invoices customers hold.
 */
@Service
public class GstReportService {
    static final int MAX_DAYS = 400;
    public static final String INVOICE = "INVOICE";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";

    public record Row(String month, String type, String placeOfSupply, double gstRate, int documents,
                      double taxableValue, double cgst, double sgst, double igst, double totalTax, double value) {
    }

    public record Totals(double taxableValue, double cgst, double sgst, double igst, double totalTax, double value) {
    }

    public record Report(LocalDate from, LocalDate to, int invoices, int creditNotes, List<Row> rows,
                         Totals invoiceTotals, Totals creditNoteTotals, Totals net) {
    }

    private static final class Acc {
        final Set<Object> documents = new HashSet<>();
        double taxable, cgst, sgst, igst, value;
    }

    private final CartRepository orders;
    private final CreditNoteRepository creditNotes;
    private final ShippingAddressRepository addresses;
    private final double defaultRate;
    private final String storeState;
    private final ZoneId zone;

    public GstReportService(CartRepository orders, CreditNoteRepository creditNotes, ShippingAddressRepository addresses,
                            @Value("${gst.default-rate:18}") double defaultRate,
                            @Value("${gst.store-state:}") String storeState,
                            @Value("${digest.zone:Asia/Kolkata}") String zone) {
        this.orders = orders;
        this.creditNotes = creditNotes;
        this.addresses = addresses;
        this.defaultRate = defaultRate;
        this.storeState = storeState == null ? "" : storeState.trim();
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim());
    }

    public LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, zone);
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ProductException("Give a from date that is not after the to date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ProductException("Choose a range of at most " + MAX_DAYS + " days");
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        record Key(YearMonth month, String type, String place, double rate) {
        }
        Map<Key, Acc> groups = new HashMap<>();

        List<Cart> invoiced = orders.findByInvoiceDateGreaterThanEqualAndInvoiceDateLessThan(start, end);
        Map<Long, ShippingAddress> addressById = new HashMap<>();
        for (ShippingAddress a : addresses.findAllById(invoiced.stream().map(Cart::getShippingAddressId)
                .filter(java.util.Objects::nonNull).distinct().toList())) {
            addressById.put(a.getId(), a);
        }
        for (Cart order : invoiced) {
            String place = placeOfSupply(addressById.get(order.getShippingAddressId()));
            boolean interState = GstCalculator.isInterState(storeState, place);
            List<GstCalculator.Item> items = new ArrayList<>();
            for (OrderItem item : order.getOrderItems()) {
                double rate = item.getGstRate() != null ? item.getGstRate() : defaultRate;
                double price = item.getUnitPrice() != null ? item.getUnitPrice() : 0;
                items.add(new GstCalculator.Item(item.getProductId(), "", null, rate, price,
                        item.getProductQuantity(), item.getProductQuantity()));
            }
            double ratio = GstCalculator.discountRatio(items, order.getDiscountAmount());
            YearMonth month = YearMonth.from(order.getInvoiceDate().atZone(zone));
            for (GstCalculator.Item item : items) {
                if (item.originalQuantity() <= 0) {
                    continue;
                }
                var line = GstCalculator.lineTax(item, item.originalQuantity(), ratio, interState);
                Acc acc = groups.computeIfAbsent(new Key(month, INVOICE, place, item.gstRate()), k -> new Acc());
                acc.documents.add(order.getOrderId());
                acc.taxable += line.taxableValue();
                acc.cgst += line.cgst();
                acc.sgst += line.sgst();
                acc.igst += line.igst();
                acc.value += line.total();
            }
        }

        List<CreditNote> notes = creditNotes.findByIssuedAtGreaterThanEqualAndIssuedAtLessThanOrderByIdAsc(start, end);
        for (CreditNote note : notes) {
            YearMonth month = YearMonth.from(note.getIssuedAt().atZone(zone));
            String place = note.getPlaceOfSupply() == null || note.getPlaceOfSupply().isBlank() ? storeState : note.getPlaceOfSupply();
            for (var line : note.getLines()) {
                Acc acc = groups.computeIfAbsent(new Key(month, CREDIT_NOTE, place, line.getGstRate()), k -> new Acc());
                acc.documents.add(note.getId());
                acc.taxable += line.getTaxableValue();
                acc.cgst += line.getCgst();
                acc.sgst += line.getSgst();
                acc.igst += line.getIgst();
                acc.value += line.getTotal();
            }
        }

        List<Row> rows = groups.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Key, Acc> e) -> e.getKey().month())
                        .thenComparing(e -> e.getKey().type().equals(INVOICE) ? 0 : 1)
                        .thenComparing(e -> e.getKey().place())
                        .thenComparingDouble(e -> e.getKey().rate()))
                .map(e -> {
                    Acc a = e.getValue();
                    double tax = round(a.cgst + a.sgst + a.igst);
                    return new Row(e.getKey().month().toString(), e.getKey().type(), e.getKey().place(), e.getKey().rate(),
                            a.documents.size(), round(a.taxable), round(a.cgst), round(a.sgst), round(a.igst), tax, round(a.value));
                })
                .toList();

        Totals invoiceTotals = totals(rows, INVOICE);
        Totals creditTotals = totals(rows, CREDIT_NOTE);
        Totals net = new Totals(round(invoiceTotals.taxableValue() - creditTotals.taxableValue()),
                round(invoiceTotals.cgst() - creditTotals.cgst()), round(invoiceTotals.sgst() - creditTotals.sgst()),
                round(invoiceTotals.igst() - creditTotals.igst()), round(invoiceTotals.totalTax() - creditTotals.totalTax()),
                round(invoiceTotals.value() - creditTotals.value()));
        return new Report(from, to, invoiced.size(), notes.size(), rows, invoiceTotals, creditTotals, net);
    }

    @Transactional(readOnly = true)
    public String exportCsv(LocalDate from, LocalDate to) {
        Report report = report(from, to);
        StringBuilder csv = new StringBuilder("month,type,placeOfSupply,gstRate,documents,taxableValue,cgst,sgst,igst,totalTax,value\r\n");
        for (Row r : report.rows()) {
            csv.append(r.month()).append(',').append(r.type()).append(',').append(OrderService.csvCell(r.placeOfSupply())).append(',')
                    .append(r.gstRate()).append(',').append(r.documents()).append(',').append(r.taxableValue()).append(',')
                    .append(r.cgst()).append(',').append(r.sgst()).append(',').append(r.igst()).append(',')
                    .append(r.totalTax()).append(',').append(r.value()).append("\r\n");
        }
        appendTotals(csv, "TOTAL_INVOICES", report.invoices(), report.invoiceTotals());
        appendTotals(csv, "TOTAL_CREDIT_NOTES", report.creditNotes(), report.creditNoteTotals());
        appendTotals(csv, "NET", null, report.net());
        return csv.toString();
    }

    private static void appendTotals(StringBuilder csv, String type, Integer documents, Totals t) {
        csv.append("TOTAL,").append(type).append(",,,").append(documents == null ? "" : documents).append(',')
                .append(t.taxableValue()).append(',').append(t.cgst()).append(',').append(t.sgst()).append(',')
                .append(t.igst()).append(',').append(t.totalTax()).append(',').append(t.value()).append("\r\n");
    }

    private static Totals totals(List<Row> rows, String type) {
        double taxable = 0, cgst = 0, sgst = 0, igst = 0, value = 0;
        for (Row r : rows) {
            if (r.type().equals(type)) {
                taxable += r.taxableValue();
                cgst += r.cgst();
                sgst += r.sgst();
                igst += r.igst();
                value += r.value();
            }
        }
        return new Totals(round(taxable), round(cgst), round(sgst), round(igst), round(cgst + sgst + igst), round(value));
    }

    private String placeOfSupply(ShippingAddress address) {
        return address != null && address.getState() != null && !address.getState().isBlank()
                ? address.getState().trim() : storeState;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
