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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnApprovalServiceTest {
    @Mock
    private CartRepository orders;
    @Mock
    private OrderService orderService;
    @Mock
    private CustomerNotifier notifier;

    private ReturnApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ReturnApprovalService(orders, orderService, notifier, true);
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
    void aDeliveredOrderCanBeRequestedAndStaffSeeItInTheQueue() {
        Cart cart = delivered();
        when(orders.findAll()).thenReturn(List.of(cart));

        Cart result = service.request(42L, "  Wrong size ");

        assertEquals("REQUESTED", result.getReturnRequestStatus());
        assertEquals("Wrong size", result.getReturnRequestReason());
        assertEquals(1, service.pending().size());
    }

    @Test
    void requestRefusesABlankReasonANonDeliveredOrderAndADuplicate() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.request(42L, " "));
        cart.setStatus(OrderStatus.SHIPPED);
        assertThrows(ProductException.class, () -> service.request(42L, "x"));
        cart.setStatus(OrderStatus.DELIVERED);
        cart.setReturnRequestStatus("REQUESTED");
        assertThrows(ProductException.class, () -> service.request(42L, "x"));
    }

    @Test
    void anExpiredReturnWindowStopsTheRequest() {
        delivered();
        doThrow(new ProductException("Return window of 7 days has expired")).when(orderService).requireWithinReturnWindow(42L);

        assertThrows(ProductException.class, () -> service.request(42L, "late"));
        verify(orders, never()).save(any(Cart.class));
    }

    @Test
    void aRejectedRequestCanBeAskedAgain() {
        Cart cart = delivered();
        cart.setReturnRequestStatus("REJECTED");

        assertEquals("REQUESTED", service.request(42L, "second try").getReturnRequestStatus());
    }

    @Test
    void approveEmailsTheCustomerAndOnlyWorksOnARequest() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.approve(42L));
        cart.setReturnRequestStatus("REQUESTED");

        Cart result = service.approve(42L);

        assertEquals("APPROVED", result.getReturnRequestStatus());
        verify(notifier).notifyOrderUpdate(eq(cart), contains("approved"), any());
    }

    @Test
    void rejectKeepsTheNoteAndSurvivesAMailFailure() {
        Cart cart = delivered();
        cart.setReturnRequestStatus("REQUESTED");
        when(notifier.notifyOrderUpdate(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        Cart result = service.reject(42L, " Used item ");

        assertEquals("REJECTED", result.getReturnRequestStatus());
        assertEquals("Used item", result.getReturnRequestNote());
    }

    @Test
    void aCustomerMayOnlyCompleteAnApprovedReturnWhileApprovalIsOn() {
        Cart cart = delivered();
        assertThrows(ProductException.class, () -> service.assertCustomerMayReturn(42L));
        cart.setReturnRequestStatus("REQUESTED");
        assertThrows(ProductException.class, () -> service.assertCustomerMayReturn(42L));
        cart.setReturnRequestStatus("APPROVED");
        service.assertCustomerMayReturn(42L);

        ReturnApprovalService off = new ReturnApprovalService(orders, orderService, notifier, false);
        cart.setReturnRequestStatus(null);
        off.assertCustomerMayReturn(42L);
        assertThrows(ProductException.class, () -> off.request(42L, "x"));
        assertTrue(service.isRequired() && !off.isRequired());
    }
}
