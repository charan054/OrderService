package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.PackingSlips;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.PaymentMethod;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ShippingAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingSlipServiceTest {
    @Mock
    private CartRepository orders;
    @Mock
    private ShippingAddressRepository addresses;
    @Mock
    private ProductClient productClient;
    @Mock
    private com.example.orderservice.repository.TrackingEventRepository tracking;

    private PackingSlipService service;

    @BeforeEach
    void setUp() {
        service = new PackingSlipService(orders, addresses, productClient,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T10:00:00Z"), java.time.ZoneOffset.UTC), tracking);
        lenient().when(productClient.getProductById(1)).thenReturn(product(1, "Soap"));
        lenient().when(productClient.getProductById(2)).thenReturn(product(2, "Dal"));
    }

    private static Product product(int id, String name) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName(name);
        return p;
    }

    private static OrderItem item(int productId, int qty, int cancelled) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(qty);
        i.setCancelledQuantity(cancelled);
        return i;
    }

    private static Cart order(long id, PaymentMethod method, OrderItem... items) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerName("Ann");
        c.setCustomerPhno(9000000001L);
        c.setStatus(OrderStatus.PLACED);
        c.setPaymentMethod(method);
        c.setTotalPrice(250);
        c.setOrderItems(new ArrayList<>(List.of(items)));
        return c;
    }

    @Test
    void slipsListOnlyUnitsStillWithTheCustomerAndTotalThemInThePickList() {
        Cart a = order(2, PaymentMethod.PHONEPE, item(1, 3, 1), item(2, 1, 0));
        Cart b = order(1, PaymentMethod.PHONEPE, item(1, 2, 0), item(2, 1, 1));
        when(orders.findByStatus(OrderStatus.PLACED)).thenReturn(new ArrayList<>(List.of(a, b)));

        PackingSlips result = service.build(null, null);

        assertEquals(List.of(1L, 2L), result.slips().stream().map(PackingSlips.Slip::orderId).toList());
        assertEquals(1, result.slips().get(0).lines().size());
        PackingSlips.PickLine soap = result.pickList().stream().filter(l -> l.productId() == 1).findFirst().orElseThrow();
        assertEquals(4, soap.quantity());
        assertEquals(2, soap.orders());
        assertEquals("Dal", result.pickList().get(0).name());
    }

    @Test
    void anUnpaidCashOrderShowsWhatToCollectAndCarriesTheGiftAndAddress() {
        Cart cash = order(5, PaymentMethod.CASH, item(1, 1, 0));
        cash.setRefundedAmount(50);
        cash.setGiftWrap(true);
        cash.setGiftMessage("Enjoy");
        cash.setShippingAddressId(8L);
        ShippingAddress a = new ShippingAddress();
        a.setLine1("12 Main St");
        a.setCity("Hyd");
        a.setPincode("500001");
        when(addresses.findById(8L)).thenReturn(Optional.of(a));
        when(orders.findAllById(List.of(5L))).thenReturn(List.of(cash));

        PackingSlips.Slip slip = service.build(null, List.of(5L)).slips().get(0);

        assertEquals(200.0, slip.amountToCollect());
        assertTrue(slip.giftWrap());
        assertEquals("Enjoy", slip.giftMessage());
        assertEquals("12 Main St, Hyd, 500001", slip.address());
        cash.setPaid(true);
        assertEquals(0.0, service.build(null, List.of(5L)).slips().get(0).amountToCollect());
    }

    @Test
    void ordersThatAreNotPlacedOrShippedAreNotPacked() {
        Cart delivered = order(7, PaymentMethod.CASH, item(1, 1, 0));
        delivered.setStatus(OrderStatus.DELIVERED);
        when(orders.findAllById(List.of(7L))).thenReturn(List.of(delivered));

        assertTrue(service.build(null, List.of(7L)).slips().isEmpty());
        assertThrows(ProductException.class, () -> service.build(OrderStatus.DELIVERED, null));
    }

    @Test
    void giftReceiptHasTheMessageAndUnitsButNoPrices() {
        Cart gift = order(9, PaymentMethod.PHONEPE, item(1, 3, 1), item(2, 1, 1));
        gift.setGiftMessage("Enjoy");
        when(orders.findById(9L)).thenReturn(Optional.of(gift));

        PackingSlips.GiftReceipt receipt = service.giftReceipt(9L);

        assertEquals("Enjoy", receipt.giftMessage());
        assertEquals(1, receipt.lines().size());
        assertEquals(2, receipt.lines().get(0).quantity());
    }

    @Test
    void giftReceiptRefusesAnOrderThatIsNotAGift() {
        when(orders.findById(9L)).thenReturn(Optional.of(order(9, PaymentMethod.PHONEPE, item(1, 1, 0))));
        assertThrows(ProductException.class, () -> service.giftReceipt(9L));
    }

    @Test
    void packedPlacedOrdersLeaveThePickListUnlessAskedFor() {
        Cart packed = order(1, PaymentMethod.PHONEPE, item(1, 1, 0));
        packed.setPackedAt(java.time.Instant.parse("2026-10-13T09:00:00Z"));
        Cart open = order(2, PaymentMethod.PHONEPE, item(1, 1, 0));
        when(orders.findByStatus(OrderStatus.PLACED)).thenReturn(new ArrayList<>(List.of(packed, open)));

        assertEquals(List.of(2L), service.build(null, null).slips().stream().map(PackingSlips.Slip::orderId).toList());
        assertEquals(2, service.build(null, null, true).slips().size());
    }

    @Test
    void markPackedOnlyTouchesPlacedUnpackedOrdersAndUnpackPutsOneBack() {
        Cart a = order(1, PaymentMethod.PHONEPE, item(1, 1, 0));
        Cart shipped = order(2, PaymentMethod.PHONEPE, item(1, 1, 0));
        shipped.setStatus(OrderStatus.SHIPPED);
        Cart already = order(3, PaymentMethod.PHONEPE, item(1, 1, 0));
        already.setPackedAt(java.time.Instant.parse("2026-10-12T09:00:00Z"));
        when(orders.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(a, shipped, already));

        assertEquals(1, service.markPacked(List.of(1L, 2L, 3L)));
        assertEquals(java.time.Instant.parse("2026-10-13T10:00:00Z"), a.getPackedAt());
        assertEquals(null, shipped.getPackedAt());
        assertThrows(ProductException.class, () -> service.markPacked(List.of()));

        when(orders.findById(1L)).thenReturn(Optional.of(a));
        when(orders.save(a)).thenReturn(a);
        assertEquals(null, service.unpack(1L).getPackedAt());
    }

    @Test
    void statusCountsPackedAndUnpackedAndListsTheStaleUnpackedOldestFirst() {
        Cart packed = order(1, PaymentMethod.CASH, item(1, 1, 0));
        packed.setPackedAt(java.time.Instant.parse("2026-10-13T09:00:00Z"));
        Cart oldest = order(2, PaymentMethod.CASH, item(1, 1, 0));
        Cart old = order(3, PaymentMethod.CASH, item(1, 1, 0));
        Cart fresh = order(4, PaymentMethod.CASH, item(1, 1, 0));
        when(orders.findByStatus(OrderStatus.PLACED)).thenReturn(List.of(packed, old, oldest, fresh));
        when(tracking.findAll()).thenReturn(List.of(
                placedEvent(1, "2026-10-01T00:00:00Z"), placedEvent(2, "2026-10-10T00:00:00Z"),
                placedEvent(3, "2026-10-11T00:00:00Z"), placedEvent(4, "2026-10-13T08:00:00Z")));

        PackingSlipService.PackingStatus st = service.status(24);

        assertEquals(3, st.unpacked());
        assertEquals(1, st.packedWaiting());
        assertEquals(List.of(2L, 3L), st.staleUnpacked());
    }

    private static com.example.orderservice.entity.TrackingEvent placedEvent(long orderId, String at) {
        com.example.orderservice.entity.TrackingEvent e = new com.example.orderservice.entity.TrackingEvent();
        e.setOrderId(orderId);
        e.setStatus(OrderStatus.PLACED);
        e.setTimestamp(java.time.Instant.parse(at));
        return e;
    }
}
