package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CreditNote;
import com.example.orderservice.entity.CreditNoteLine;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CreditNoteRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The monthly GST report against the real (in-memory) database; the store is in Karnataka, IST months. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"gst.store-state=Karnataka", "digest.zone=Asia/Kolkata"})
class GstReportServiceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Autowired
    private GstReportService service;
    @Autowired
    private CartRepository orders;
    @Autowired
    private CreditNoteRepository notes;
    @Autowired
    private ShippingAddressRepository addresses;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    @BeforeEach
    void reset() {
        notes.deleteAll();
        orders.deleteAll();
    }

    private OrderItem item(int productId, int qty, double price, Double rate) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(qty);
        i.setUnitPrice(price);
        i.setGstRate(rate);
        return i;
    }

    private Cart invoiced(String invoiceDate, String state, double discount, OrderItem... items) {
        Cart c = new Cart();
        c.setCustomerName("Asha");
        c.setCustomerPhno(9876500111L);
        c.setStatus(OrderStatus.DELIVERED);
        c.setPaymentMethod(PaymentMethod.CASH);
        c.setDiscountAmount(discount);
        c.setInvoiceNumber("CM/2026-27/" + System.nanoTime());
        c.setInvoiceDate(Instant.parse(invoiceDate));
        if (state != null) {
            ShippingAddress a = new ShippingAddress();
            a.setCustomerPhno(c.getCustomerPhno());
            a.setLine1("1 Road");
            a.setCity("City");
            a.setState(state);
            a.setPincode("400001");
            c.setShippingAddressId(addresses.save(a).getId());
        }
        c.setOrderItems(new ArrayList<>(List.of(items)));
        return orders.save(c);
    }

    private CreditNote note(long orderId, String issuedAt, String state, double rate, double taxable, double cgst, double sgst, double igst) {
        CreditNote n = new CreditNote();
        n.setNumber("CM/CN/2026-27/" + System.nanoTime());
        n.setOrderId(orderId);
        n.setIssuedAt(Instant.parse(issuedAt));
        n.setReason("RETURNED");
        n.setPlaceOfSupply(state);
        CreditNoteLine l = new CreditNoteLine();
        l.setProductId(1);
        l.setProductName("Soap");
        l.setGstRate(rate);
        l.setQuantity(1);
        l.setTaxableValue(taxable);
        l.setCgst(cgst);
        l.setSgst(sgst);
        l.setIgst(igst);
        l.setTotal(taxable + cgst + sgst + igst);
        n.getLines().add(l);
        return notes.save(n);
    }

    @Test
    void groupsInvoicesAndCreditNotesByMonthStateAndRateWithTheNet() {
        invoiced("2026-09-30T10:00:00Z", null, 0, item(1, 1, 118, 18.0));
        invoiced("2026-10-08T05:00:00Z", null, 0, item(2, 4, 105, 5.0));
        Cart far = invoiced("2026-10-08T06:00:00Z", "Maharashtra", 0, item(1, 2, 118, 18.0));
        note(far.getOrderId(), "2026-10-20T06:00:00Z", "Maharashtra", 18, 100, 0, 0, 18);

        GstReportService.Report r = service.report(FROM, TO);

        assertThat(r.invoices()).isEqualTo(3);
        assertThat(r.creditNotes()).isEqualTo(1);
        assertThat(r.rows()).extracting(GstReportService.Row::month, GstReportService.Row::type,
                GstReportService.Row::placeOfSupply, GstReportService.Row::gstRate).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-09", "INVOICE", "Karnataka", 18.0),
                org.assertj.core.groups.Tuple.tuple("2026-10", "INVOICE", "Karnataka", 5.0),
                org.assertj.core.groups.Tuple.tuple("2026-10", "INVOICE", "Maharashtra", 18.0),
                org.assertj.core.groups.Tuple.tuple("2026-10", "CREDIT_NOTE", "Maharashtra", 18.0));
        GstReportService.Row sept = r.rows().get(0);
        assertThat(sept.taxableValue()).isEqualTo(100.0);
        assertThat(sept.cgst()).isEqualTo(9.0);
        assertThat(sept.sgst()).isEqualTo(9.0);
        assertThat(sept.value()).isEqualTo(118.0);
        GstReportService.Row interState = r.rows().get(2);
        assertThat(interState.igst()).isEqualTo(36.0);
        assertThat(interState.cgst()).isZero();
        assertThat(interState.taxableValue()).isEqualTo(200.0);
        assertThat(r.invoiceTotals().totalTax()).isEqualTo(18.0 + 20.0 + 36.0);
        assertThat(r.creditNoteTotals().totalTax()).isEqualTo(18.0);
        assertThat(r.net().totalTax()).isEqualTo(56.0);
        assertThat(r.net().taxableValue()).isEqualTo(600.0);
        assertThat(r.net().value()).isEqualTo(118.0 + 420.0 + 236.0 - 118.0);
    }

    // 30 Sep 22:30 IST is still September; 30 Sep 19:00 UTC is already 1 Oct 00:30 IST.
    @Test
    void monthsAndTheRangeFollowIndianTime() {
        invoiced("2026-09-30T17:00:00Z", null, 0, item(1, 1, 118, 18.0));
        invoiced("2026-09-30T19:00:00Z", null, 0, item(1, 1, 118, 18.0));

        GstReportService.Report all = service.report(FROM, TO);
        assertThat(all.rows()).extracting(GstReportService.Row::month).containsExactly("2026-09", "2026-10");

        GstReportService.Report octOnly = service.report(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
        assertThat(octOnly.invoices()).isEqualTo(1);
        GstReportService.Report sepOnly = service.report(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30));
        assertThat(sepOnly.invoices()).isEqualTo(1);
    }

    @Test
    void theToDateIsInclusiveAndOrdersOutsideTheRangeAreLeftOut() {
        invoiced("2026-10-31T10:00:00Z", null, 0, item(1, 1, 118, 18.0));
        invoiced("2026-11-01T10:00:00Z", null, 0, item(1, 1, 118, 18.0));
        invoiced("2026-08-31T10:00:00Z", null, 0, item(1, 1, 118, 18.0));

        assertThat(service.report(FROM, TO).invoices()).isEqualTo(1);
    }

    @Test
    void aCouponLowersTheTaxableValueAndAnUnratedOldLineUsesTheDefaultRate() {
        invoiced("2026-10-08T05:00:00Z", null, 23.6, item(1, 2, 118, null)); // 10% off, no recorded rate -> 18%

        GstReportService.Row row = service.report(FROM, TO).rows().get(0);

        assertThat(row.gstRate()).isEqualTo(18.0);
        assertThat(row.value()).isEqualTo(212.4);
        assertThat(row.taxableValue()).isEqualTo(180.0);
        assertThat(row.totalTax()).isEqualTo(32.4);
    }

    @Test
    void anOrderStillCountsAsAnInvoiceAfterItWasCancelledAndTheNoteNetsItOut() {
        Cart o = invoiced("2026-10-08T05:00:00Z", null, 0, item(1, 1, 118, 18.0));
        o.setStatus(OrderStatus.CANCELLED);
        orders.save(o);
        note(o.getOrderId(), "2026-10-09T05:00:00Z", "Karnataka", 18, 100, 9, 9, 0);

        GstReportService.Report r = service.report(FROM, TO);

        assertThat(r.net().totalTax()).isZero();
        assertThat(r.net().value()).isZero();
    }

    @Test
    void rejectsAnEmptyOrTooLongRange() {
        assertThatThrownBy(() -> service.report(TO, FROM)).isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> service.report(null, TO)).isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> service.report(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1))).isInstanceOf(ProductException.class)
                .hasMessageContaining("400");
    }

    @Test
    void anEmptyRangeGivesZeroTotalsNotAnError() {
        GstReportService.Report r = service.report(FROM, TO);

        assertThat(r.rows()).isEmpty();
        assertThat(r.net().totalTax()).isZero();
    }

    @Test
    void theCsvListsTheRowsThenTheTotalsAndQuotesAStateWithAComma() {
        invoiced("2026-10-08T05:00:00Z", "Dadra, Nagar Haveli", 0, item(1, 1, 118, 18.0));

        String csv = service.exportCsv(FROM, TO);
        String[] lines = csv.split("\r\n");

        assertThat(lines[0]).isEqualTo("month,type,placeOfSupply,gstRate,documents,taxableValue,cgst,sgst,igst,totalTax,value");
        assertThat(lines[1]).isEqualTo("2026-10,INVOICE,\"Dadra, Nagar Haveli\",18.0,1,100.0,0.0,0.0,18.0,18.0,118.0");
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("TOTAL,TOTAL_INVOICES,,,1,100.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("TOTAL,TOTAL_CREDIT_NOTES,,,0,0.0"));
        assertThat(lines[lines.length - 1]).startsWith("TOTAL,NET,,,,100.0");
    }
}
