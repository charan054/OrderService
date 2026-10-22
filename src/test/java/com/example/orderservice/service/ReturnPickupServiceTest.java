package com.example.orderservice.service;

import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnPickupServiceTest {
    @Mock
    private CartRepository orders;
    @Mock
    private OrderService orderService;
    @Mock
    private CustomerNotifier notifier;
    @Mock
    private com.example.orderservice.repository.ItemReturnPickupRepository itemPickups;

    private ReturnPickupService service;

    @BeforeEach
    void setUp() {
        service = new ReturnPickupService(orders, itemPickups, orderService, notifier,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T10:00:00Z"), java.time.ZoneOffset.UTC), true);
        lenient().when(itemPickups.save(any(com.example.orderservice.entity.ItemReturnPickup.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(itemPickups.findByOrderIdAndStatusIn(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of());
        lenient().when(orders.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Cart delivered() {
        Cart cart = new Cart();
        cart.setOrderId(42L);
        cart.setStatus(OrderStatus.DELIVERED);
        lenient().when(orders.findById(42L)).thenReturn(Optional.of(cart));
        return cart;
    }

    @Test
    void scheduleBooksThePickupWithoutRefundingAnything() {
        delivered();

        Cart result = service.schedule(42L, " wrong size ", "STORE_CREDIT");

        assertEquals("AWAITING_PICKUP", result.getReturnPickupStatus());
        assertEquals("wrong size", result.getReturnReason());
        assertEquals("STORE_CREDIT", result.getReturnRefundTo());
        assertEquals(OrderStatus.DELIVERED, result.getStatus());
        verify(orderService, never()).returnOrderByStaff(org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void scheduleRefusesABlankReasonANonDeliveredOrderAndASecondBooking() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.schedule(42L, " ", null));
        cart.setStatus(OrderStatus.SHIPPED);
        assertThrows(ProductException.class, () -> service.schedule(42L, "x", null));
        cart.setStatus(OrderStatus.DELIVERED);
        cart.setReturnPickupStatus("AWAITING_PICKUP");
        assertThrows(ProductException.class, () -> service.schedule(42L, "x", null));
    }

    @Test
    void theStepsMustHappenInOrderAndOnlyReceivedRefunds() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.markPickedUp(42L));
        assertThrows(ProductException.class, () -> service.markReceived(42L));
        cart.setReturnPickupStatus("AWAITING_PICKUP");
        cart.setReturnReason("damaged");
        cart.setReturnRefundTo("STORE_CREDIT");
        assertThrows(ProductException.class, () -> service.markReceived(42L));

        assertEquals("PICKED_UP", service.markPickedUp(42L).getReturnPickupStatus());
        verify(notifier).notifyOrderUpdate(eq(cart), contains("picked up"), any());
        verify(orderService, never()).returnOrderByStaff(org.mockito.ArgumentMatchers.anyLong(), any(), any());

        when(orderService.returnOrderByStaff(42L, "damaged", "STORE_CREDIT")).thenReturn(cart);
        service.markReceived(42L);
        verify(orderService).returnOrderByStaff(42L, "damaged", "STORE_CREDIT");
    }

    @Test
    void openListsOnlyDeliveredOrdersWithAPickupInProgress() {
        Cart a = delivered();
        a.setReturnPickupStatus("PICKED_UP");
        Cart returned = new Cart();
        returned.setOrderId(43L);
        returned.setStatus(OrderStatus.RETURNED);
        returned.setReturnPickupStatus("PICKED_UP");
        Cart none = new Cart();
        none.setOrderId(44L);
        none.setStatus(OrderStatus.DELIVERED);
        when(orders.findAll()).thenReturn(List.of(none, returned, a));

        assertEquals(List.of(a), service.open());
    }

    private com.example.orderservice.entity.ItemReturnPickup booked(long id, String status) {
        com.example.orderservice.entity.ItemReturnPickup p = new com.example.orderservice.entity.ItemReturnPickup();
        p.setId(id);
        p.setOrderId(42L);
        p.setProductId(7);
        p.setQuantity(2);
        p.setReason("damaged");
        p.setRefundTo("STORE_CREDIT");
        p.setStatus(status);
        when(itemPickups.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void scheduleItemBooksAPickupAfterCheckingTheUnitsAreStillReturnable() {
        Cart cart = delivered();
        com.example.orderservice.entity.ItemReturnPickup earlier = new com.example.orderservice.entity.ItemReturnPickup();
        earlier.setProductId(7);
        earlier.setQuantity(1);
        when(itemPickups.findByOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(42L), any())).thenReturn(List.of(earlier));

        service.scheduleItem(42L, 7, 2, " broken ", "STORE_CREDIT");

        verify(orderService).requireReturnableItem(cart, 7, 2, 1);
        org.mockito.ArgumentCaptor<com.example.orderservice.entity.ItemReturnPickup> saved =
                org.mockito.ArgumentCaptor.forClass(com.example.orderservice.entity.ItemReturnPickup.class);
        verify(itemPickups).save(saved.capture());
        assertEquals("AWAITING_PICKUP", saved.getValue().getStatus());
        assertEquals("broken", saved.getValue().getReason());
        verify(orderService, never()).returnItemByStaff(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void scheduleItemRefusesBlankReasonNonDeliveredAndAWholeOrderPickup() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.scheduleItem(42L, 7, 1, " ", null));
        cart.setReturnPickupStatus("AWAITING_PICKUP");
        assertThrows(ProductException.class, () -> service.scheduleItem(42L, 7, 1, "x", null));
        cart.setReturnPickupStatus(null);
        cart.setStatus(OrderStatus.SHIPPED);
        assertThrows(ProductException.class, () -> service.scheduleItem(42L, 7, 1, "x", null));
    }

    @Test
    void aWholeOrderPickupIsRefusedWhileItemPickupsAreOpen() {
        delivered();
        when(itemPickups.findByOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(42L), any()))
                .thenReturn(List.of(new com.example.orderservice.entity.ItemReturnPickup()));
        assertThrows(ProductException.class, () -> service.schedule(42L, "x", null));
    }

    @Test
    void itemPickupStepsRunInOrderAndOnlyReceivedRefunds() {
        delivered();
        booked(5, "AWAITING_PICKUP");
        assertThrows(ProductException.class, () -> service.markItemReceived(5));

        assertEquals("PICKED_UP", service.markItemPickedUp(5).getStatus());
        verify(orderService, never()).returnItemByStaff(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), org.mockito.ArgumentMatchers.anyLong());

        assertEquals("RECEIVED", service.markItemReceived(5).getStatus());
        verify(orderService).returnItemByStaff(42L, 7, 2, "damaged", "STORE_CREDIT", 5L);
        assertThrows(ProductException.class, () -> service.markItemPickedUp(5));
    }
}
