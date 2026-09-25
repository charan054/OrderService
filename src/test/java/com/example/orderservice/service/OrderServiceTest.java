package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String SERVICE_KEY = "test-service-key";
    private static final long CUSTOMER = 9876543210L;

    @Mock
    private CartRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ProductClient productClient;

    @InjectMocks
    private OrderService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "serviceApiKey", SERVICE_KEY);
    }

    // Simulates the database assigning the generated id on first save, the way JPA actually would.
    private void stubCartSaveAssignsAnId() {
        when(orderRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart cart = invocation.getArgument(0);
            if (cart.getOrderId() == null) {
                cart.setOrderId(42L);
            }
            return cart;
        });
    }

    private Product product(int id, double price, int stock) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName("Widget " + id);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    private OrderItem item(int productId, int quantity) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(quantity);
        return i;
    }

    private Cart cart(long phno, OrderItem... items) {
        Cart c = new Cart();
        c.setCustomerName("Buyer");
        c.setCustomerPhno(phno);
        c.setOrderItems(new ArrayList<>(List.of(items)));
        return c;
    }

    // ---------- order() ----------

    @Test
    void orderRejectsAnInvalidPhoneNumber() {
        Cart cart = cart(12345, item(1, 1));
        assertThrows(ProductException.class, () -> service.order(cart));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderThrowsWhenAProductDoesNotExist() {
        when(productClient.getProductById(1)).thenReturn(null);
        Cart cart = cart(CUSTOMER, item(1, 1));
        assertThrows(ProductException.class, () -> service.order(cart));
        verify(orderRepository, never()).save(any());
    }

    // Regression: a later item failing must never leave an earlier item's stock decremented with no order saved.
    @Test
    void orderThrowsWhenQuantityExceedsStockAndTouchesNoStockOrOrder() {
        when(productClient.getProductById(1)).thenReturn(product(1, 45.0, 5));
        when(productClient.getProductById(2)).thenReturn(product(2, 10.0, 1));
        Cart cart = cart(CUSTOMER, item(1, 1), item(2, 99));

        assertThrows(ProductException.class, () -> service.order(cart));

        verify(productClient, never()).updateProductStock(any(), anyInt(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void orderComputesTotalPriceSetsRealOrderIdAndDecrementsStockForEveryItem() {
        stubCartSaveAssignsAnId();
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 10));
        when(productClient.getProductById(2)).thenReturn(product(2, 45.0, 10));
        Cart cart = cart(CUSTOMER, item(1, 2), item(2, 1));

        Cart result = service.order(cart);

        assertEquals(1045.0, result.getTotalPrice());
        assertEquals(42L, result.getOrderId());
        for (OrderItem oi : result.getOrderItems()) {
            assertEquals(42L, oi.getOrderId());
        }
        verify(productClient).updateProductStock(SERVICE_KEY, 1, -2);
        verify(productClient).updateProductStock(SERVICE_KEY, 2, -1);
        verify(orderRepository, times(2)).save(any());
    }

    // ---------- ordersOfPhno ----------

    @Test
    void ordersOfPhnoRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.ordersOfPhno(555));
    }

    @Test
    void ordersOfPhnoDelegatesToTheRepository() {
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cart(CUSTOMER)));
        assertEquals(1, service.ordersOfPhno(CUSTOMER).size());
    }

    // ---------- deleteProduct ----------

    @Test
    void deleteProductRestoresStockAndRecomputesTotalPrice() {
        OrderItem toRemove = item(1, 2);
        toRemove.setId(101L);
        OrderItem toKeep = item(2, 1);
        toKeep.setId(102L);
        Cart cart = cart(CUSTOMER, toRemove, toKeep);
        cart.setTotalPrice(2 * 500.0 + 45.0); // 1045.0, matching the two items above
        when(orderRepository.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(cart));
        when(productClient.getProductById(1)).thenReturn(product(1, 500.0, 8));

        List<Cart> result = service.deleteProduct(CUSTOMER, 1);

        assertEquals(1, result.get(0).getOrderItems().size());
        assertEquals(45.0, result.get(0).getTotalPrice());
        verify(productClient).updateProductStock(SERVICE_KEY, 1, 2);
        verify(orderItemRepository).deleteById(toRemove.getId());
    }

    @Test
    void deleteProductRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.deleteProduct(555, 1));
    }

    // ---------- getProducts ----------

    @Test
    void getProductsDelegatesToTheProductClient() {
        when(productClient.findAll()).thenReturn(List.of(product(1, 9.99, 10)));
        assertEquals(1, service.getProducts().size());
    }
}
