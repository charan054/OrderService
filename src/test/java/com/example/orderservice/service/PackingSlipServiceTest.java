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

    private PackingSlipService service;

    @BeforeEach
    void setUp() {
        service = new PackingSlipService(orders, addresses, productClient);
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
}
