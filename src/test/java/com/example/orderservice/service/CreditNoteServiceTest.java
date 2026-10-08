package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.CreditNoteView;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CreditNote;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.CreditNoteRepository;
import com.example.orderservice.repository.CreditNoteSequenceRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/** Credit notes against the real (in-memory) database; the store is in Karnataka. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"gst.store-state=Karnataka", "gst.invoice-prefix=CM"})
class CreditNoteServiceTest {
    @Autowired
    private CreditNoteService service;
    @Autowired
    private CreditNoteRepository notes;
    @Autowired
    private CreditNoteSequenceRepository sequences;
    @Autowired
    private CartRepository orders;
    @Autowired
    private ShippingAddressRepository addresses;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private static final java.util.concurrent.atomic.AtomicInteger INVOICES = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void reset() {
        notes.deleteAll();
        orders.deleteAll();
        sequences.deleteAll();
        Product soap = new Product();
        soap.setProductName("Soap");
        soap.setProductPrice(118);
        soap.setHsnCode("3401");
        when(productClient.getProductById(anyInt())).thenReturn(soap);
    }

    private OrderItem item(int productId, int qty, double price, Double rate) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(qty);
        i.setUnitPrice(price);
        i.setGstRate(rate);
        return i;
    }

    private Cart invoicedOrder(String state, double discount, OrderItem... items) {
        Cart c = new Cart();
        c.setCustomerName("Asha");
        c.setCustomerPhno(9876500101L);
        c.setStatus(OrderStatus.PLACED);
        c.setPaymentMethod(PaymentMethod.CASH);
        c.setDiscountAmount(discount);
        c.setInvoiceNumber("CM/2026-27/T" + INVOICES.incrementAndGet());
        if (state != null) {
            ShippingAddress a = new ShippingAddress();
            a.setCustomerPhno(c.getCustomerPhno());
            a.setLine1("1 Road");
            a.setCity("City");
            a.setState(state);
            a.setPincode("560001");
            c.setShippingAddressId(addresses.save(a).getId());
        }
        c.setOrderItems(new ArrayList<>(List.of(items)));
        return orders.save(c);
    }

    @Test
    void cancellingOneUnitIssuesANumberedNoteReversingThatUnitsTax() {
        OrderItem soap = item(1, 2, 118, 18.0);
        Cart order = invoicedOrder("Karnataka", 0, soap);
        soap.setCancelledQuantity(1);

        CreditNote note = service.issue(order, List.of(new CreditNoteService.Change(soap, 2, 1)), "CANCELLED").orElseThrow();

        assertThat(note.getNumber()).startsWith("CM/CN/").endsWith("/000001");
        assertThat(note.getInvoiceNumber()).isEqualTo(order.getInvoiceNumber());
        assertThat(note.getOrderId()).isEqualTo(order.getOrderId());
        assertThat(note.getReason()).isEqualTo("CANCELLED");
        assertThat(note.isInterState()).isFalse();
        assertThat(note.getTaxableValue()).isEqualTo(100.0);
        assertThat(note.getCgst()).isEqualTo(9.0);
        assertThat(note.getSgst()).isEqualTo(9.0);
        assertThat(note.getIgst()).isEqualTo(0.0);
        assertThat(note.getTotal()).isEqualTo(118.0);
        assertThat(note.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductName()).isEqualTo("Soap");
            assertThat(l.getHsnCode()).isEqualTo("3401");
            assertThat(l.getQuantity()).isEqualTo(1);
            assertThat(l.getGstRate()).isEqualTo(18.0);
        });
    }

    @Test
    void anotherStateGetsIgstAndNumbersKeepCounting() {
        OrderItem soap = item(1, 2, 118, 18.0);
        Cart order = invoicedOrder("Maharashtra", 0, soap);

        CreditNote first = service.issue(order, List.of(new CreditNoteService.Change(soap, 2, 1)), "CANCELLED").orElseThrow();
        CreditNote second = service.issue(order, List.of(new CreditNoteService.Change(soap, 1, 0)), "RETURNED").orElseThrow();

        assertThat(first.isInterState()).isTrue();
        assertThat(first.getIgst()).isEqualTo(18.0);
        assertThat(first.getCgst()).isEqualTo(0.0);
        assertThat(first.getPlaceOfSupply()).isEqualTo("Maharashtra");
        assertThat(second.getNumber()).endsWith("/000002");
        assertThat(second.getReason()).isEqualTo("RETURNED");
    }

    @Test
    void aWholeOrderCancelIsOneNoteWithALinePerProduct() {
        OrderItem a = item(1, 2, 118, 18.0);
        OrderItem b = item(2, 1, 105, 5.0);
        Cart order = invoicedOrder("Karnataka", 0, a, b);

        CreditNote note = service.issue(order, List.of(new CreditNoteService.Change(a, 2, 0), new CreditNoteService.Change(b, 1, 0)), "CANCELLED").orElseThrow();

        assertThat(note.getLines()).hasSize(2);
        assertThat(note.getTotal()).isEqualTo(341.0);
        assertThat(note.getCgst()).isEqualTo(18.0 + 2.5);
        assertThat(notes.count()).isEqualTo(1);
    }

    @Test
    void aCouponLowersWhatIsReversed() {
        OrderItem soap = item(1, 2, 118, 18.0);
        Cart order = invoicedOrder("Karnataka", 23.6, soap); // 10% off the order

        CreditNote note = service.issue(order, List.of(new CreditNoteService.Change(soap, 2, 1)), "CANCELLED").orElseThrow();

        assertThat(note.getTotal()).isEqualTo(106.2);
        assertThat(note.getTaxableValue()).isEqualTo(90.0);
    }

    @Test
    void anOrderThatWasNeverInvoicedHasNothingToReverse() {
        OrderItem soap = item(1, 1, 118, 18.0);
        Cart order = invoicedOrder("Karnataka", 0, soap);
        order.setInvoiceNumber(null);

        assertThat(service.issue(order, List.of(new CreditNoteService.Change(soap, 1, 0)), "CANCELLED")).isEmpty();
        assertThat(notes.count()).isZero();
    }

    @Test
    void nothingIsIssuedForAChangeThatRemovesNoValue() {
        OrderItem free = item(1, 1, 0, 18.0);
        Cart order = invoicedOrder("Karnataka", 0, free);

        assertThat(service.issue(order, List.of(new CreditNoteService.Change(free, 1, 0)), "CANCELLED")).isEmpty();
        assertThat(service.issue(order, List.of(new CreditNoteService.Change(free, 1, 1)), "CANCELLED")).isEmpty();
        assertThat(sequences.count()).isZero();
    }

    @Test
    void anOldOrderWithoutARecordedRateUsesTheCatalogRateThenTheDefault() {
        Product withRate = new Product();
        withRate.setProductName("Tea");
        withRate.setProductPrice(105);
        withRate.setGstRate(5.0);
        when(productClient.getProductById(1)).thenReturn(withRate);
        OrderItem tea = item(1, 1, 105, null);
        OrderItem soap = item(2, 1, 118, null); // catalog has no rate -> default 18
        Cart order = invoicedOrder("Karnataka", 0, tea, soap);

        CreditNote note = service.issue(order, List.of(new CreditNoteService.Change(tea, 1, 0), new CreditNoteService.Change(soap, 1, 0)), "CANCELLED").orElseThrow();

        assertThat(note.getLines()).extracting(l -> l.getGstRate()).containsExactly(5.0, 18.0);
        assertThat(note.getCgst() + note.getSgst()).isEqualTo(23.0);
    }

    @Test
    void theInvoiceCanListItsNotesInOrder() {
        OrderItem soap = item(1, 3, 118, 18.0);
        Cart order = invoicedOrder("Karnataka", 0, soap);
        service.issue(order, List.of(new CreditNoteService.Change(soap, 3, 2)), "CANCELLED");
        service.issue(order, List.of(new CreditNoteService.Change(soap, 2, 1)), "RETURNED");

        List<CreditNoteView> views = service.forOrder(order.getOrderId());

        assertThat(views).extracting(CreditNoteView::reason).containsExactly("CANCELLED", "RETURNED");
        assertThat(views.get(0).lines()).singleElement().satisfies(l -> assertThat(l.productName()).isEqualTo("Soap"));
        assertThat(service.forOrder(-1)).isEmpty();
        assertThat(Optional.of(views.get(1).number())).hasValueSatisfying(n -> assertThat(n).endsWith("/000002"));
    }
}
