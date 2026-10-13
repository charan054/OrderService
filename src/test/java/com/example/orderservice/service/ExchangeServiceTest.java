package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.ExchangeRequest;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.ExchangeRequestRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeServiceTest {
    @Mock
    private ExchangeRequestRepository exchanges;
    @Mock
    private CartRepository orders;
    @Mock
    private OrderService orderService;
    @Mock
    private ProductClient productClient;
    @Mock
    private CustomerNotifier notifier;
    @Mock
    private StoreCreditService storeCredit;
    @Mock
    private CreditNoteService creditNotes;

    private ExchangeService service;

    @BeforeEach
    void setUp() {
        service = new ExchangeService(exchanges, orders, orderService, productClient, notifier, storeCredit, creditNotes,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T10:00:00Z"), java.time.ZoneOffset.UTC), "key");
        lenient().when(orderService.orderLock(anyLong())).thenReturn(new Object());
        lenient().when(exchanges.save(any(ExchangeRequest.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(exchanges.findByOrderIdAndStatusIn(anyLong(), any())).thenReturn(List.of());
    }

    private Cart delivered(double unitPrice, int quantity) {
        Cart cart = new Cart();
        cart.setOrderId(7L);
        cart.setCustomerPhno(9000000001L);
        cart.setStatus(OrderStatus.DELIVERED);
        OrderItem item = new OrderItem();
        item.setProductId(1);
        item.setProductQuantity(quantity);
        item.setUnitPrice(unitPrice);
        cart.setOrderItems(List.of(item));
        lenient().when(orders.findById(7L)).thenReturn(Optional.of(cart));
        return cart;
    }

    private void replacement(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        lenient().when(productClient.getProductById(id)).thenReturn(p);
    }

    private ExchangeRequest stored(String status) {
        ExchangeRequest r = new ExchangeRequest();
        r.setId(5L);
        r.setOrderId(7L);
        r.setProductId(1);
        r.setQuantity(2);
        r.setReplacementProductId(9);
        r.setStatus(status);
        lenient().when(exchanges.findById(5L)).thenReturn(Optional.of(r));
        delivered(100, 3);
        return r;
    }

    @Test
    void requestStoresAWaitingExchangeAndMovesNoStock() {
        delivered(100, 3);
        replacement(9, 80, 5);

        ExchangeRequest r = service.request(7L, 1, 2, 9, " wrong size ");

        assertEquals("REQUESTED", r.getStatus());
        assertEquals("wrong size", r.getReason());
        verify(productClient, never()).updateProductStock(any(), any(), any());
    }

    @Test
    void requestRefusesAnUnaffordableDearerReplacementOutOfStockAndTooManyUnits() {
        delivered(100, 3);
        replacement(9, 120, 5);
        replacement(8, 50, 1);
        when(storeCredit.balance(9000000001L)).thenReturn(10.0);
        assertThrows(ProductException.class, () -> service.request(7L, 1, 1, 9, "x"));
        assertThrows(ProductException.class, () -> service.request(7L, 1, 2, 8, "x"));
        replacement(7, 50, 9);
        assertThrows(ProductException.class, () -> service.request(7L, 1, 4, 7, "x"));
        assertThrows(ProductException.class, () -> service.request(7L, 1, 1, 7, " "));
    }

    @Test
    void requestCountsUnitsAlreadyUnderAnExchange() {
        delivered(100, 3);
        replacement(9, 80, 5);
        ExchangeRequest earlier = new ExchangeRequest();
        earlier.setProductId(1);
        earlier.setQuantity(2);
        when(exchanges.findByOrderIdAndStatusIn(eq(7L), any())).thenReturn(List.of(earlier));

        assertThrows(ProductException.class, () -> service.request(7L, 1, 2, 9, "x"));
        service.request(7L, 1, 1, 9, "x");
    }

    @Test
    void requestNeedsADeliveredOrder() {
        Cart cart = delivered(100, 3);
        cart.setStatus(OrderStatus.SHIPPED);
        replacement(9, 80, 5);
        assertThrows(ProductException.class, () -> service.request(7L, 1, 1, 9, "x"));
    }

    @Test
    void approveSetsTheReplacementAsideInStock() {
        stored("REQUESTED");
        replacement(9, 80, 5);

        ExchangeRequest r = service.approve(5L);

        assertEquals("APPROVED", r.getStatus());
        verify(productClient).updateProductStock("key", 9, -2);
    }

    @Test
    void approveFailsWhenTheReplacementRanOut() {
        stored("REQUESTED");
        replacement(9, 80, 1);
        assertThrows(ProductException.class, () -> service.approve(5L));
        verify(productClient, never()).updateProductStock(any(), any(), any());
    }

    @Test
    void receivedRestocksTheOldItemOnlyAfterApproval() {
        ExchangeRequest r = stored("REQUESTED");
        assertThrows(ProductException.class, () -> service.markReceived(5L));
        r.setStatus("APPROVED");

        assertEquals("RECEIVED", service.markReceived(5L).getStatus());
        verify(productClient).updateProductStock("key", 1, 2);
    }

    @Test
    void rejectAndWithdrawOnlyWorkWhileWaiting() {
        ExchangeRequest r = stored("REQUESTED");
        assertEquals("REJECTED", service.reject(5L, "out of policy").getStatus());
        assertThrows(ProductException.class, () -> service.reject(5L, null));
        r.setStatus("REQUESTED");
        assertEquals("CANCELLED", service.cancel(5L).getStatus());
        r.setStatus("APPROVED");
        assertThrows(ProductException.class, () -> service.cancel(5L));
    }

    @Test
    void aDearerReplacementIsPaidFromStoreCreditOnApproval() {
        ExchangeRequest r = stored("REQUESTED");
        r.setPriceDifference(40);
        r.setCustomerPhno(9000000001L);
        replacement(9, 120, 5);

        service.approve(5L);

        verify(storeCredit).spend(eq(9000000001L), eq(40.0), eq(7L), any());
    }

    @Test
    void anApprovalThatCannotSetStockAsideGivesTheCreditBack() {
        ExchangeRequest r = stored("REQUESTED");
        r.setPriceDifference(40);
        r.setCustomerPhno(9000000001L);
        replacement(9, 120, 5);
        when(productClient.updateProductStock(any(), any(), any())).thenThrow(new IllegalStateException("down"));

        assertThrows(IllegalStateException.class, () -> service.approve(5L));
        verify(storeCredit).credit(eq(9000000001L), eq(40.0), eq(com.example.orderservice.entity.StoreCreditTransaction.Type.REVERSED), eq(7L), any());
    }

    @Test
    void aCheaperReplacementRefundsTheDifferenceWhenTheOldItemIsBack() {
        ExchangeRequest r = stored("APPROVED");
        r.setPriceDifference(-30);
        r.setCustomerPhno(9000000001L);

        service.markReceived(5L);

        verify(storeCredit).credit(eq(9000000001L), eq(30.0), eq(com.example.orderservice.entity.StoreCreditTransaction.Type.REFUND), eq(7L), any());
        verify(creditNotes).issueValueReduction(any(Cart.class), eq(1), eq(2), eq(30.0), eq("EXCHANGE"));
    }

    @Test
    void aFailingCreditNoteDoesNotUndoTheExchange() {
        ExchangeRequest r = stored("APPROVED");
        r.setPriceDifference(-30);
        r.setCustomerPhno(9000000001L);
        when(creditNotes.issueValueReduction(any(Cart.class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(), any())).thenThrow(new IllegalStateException("db"));

        assertEquals("RECEIVED", service.markReceived(5L).getStatus());
    }

    @Test
    void noCreditNoteForADearerOrSamePriceSwap() {
        ExchangeRequest r = stored("APPROVED");
        r.setPriceDifference(25);

        service.markReceived(5L);

        verify(creditNotes, never()).issueValueReduction(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(), any());
    }

    @Test
    void requestStoresTheSignedPriceDifference() {
        delivered(100, 3);
        replacement(9, 70, 5);
        replacement(8, 100, 5);
        replacement(7, 120, 5);
        when(storeCredit.balance(9000000001L)).thenReturn(500.0);

        assertEquals(-60.0, service.request(7L, 1, 2, 9, "x").getPriceDifference());
        assertEquals(0.0, service.request(7L, 1, 1, 8, "x").getPriceDifference());
        assertEquals(20.0, service.request(7L, 1, 1, 7, "x").getPriceDifference());
    }

    @Test
    void approvingADearerExchangeIssuesASupplementaryInvoiceForTheExtra() {
        ExchangeRequest r = stored("REQUESTED");
        r.setPriceDifference(40);
        r.setCustomerPhno(9000000001L);
        replacement(9, 120, 5);

        service.approve(5L);

        verify(creditNotes).issueSupplementary(any(Cart.class), eq(9), eq(2), eq(40.0), eq("EXCHANGE"));
    }

    @Test
    void aSamePriceExchangeIssuesNoSupplementaryInvoice() {
        stored("REQUESTED");
        replacement(9, 100, 5);

        service.approve(5L);

        verify(creditNotes, never()).issueSupplementary(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble(), any());
    }

    @Test
    void mineListsACustomersExchangesNewestFirst() {
        ExchangeRequest r = new ExchangeRequest();
        when(exchanges.findByCustomerPhnoOrderByIdDesc(9000000001L)).thenReturn(List.of(r));
        assertEquals(List.of(r), service.mine(9000000001L));
    }
}
