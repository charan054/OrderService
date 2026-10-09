package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.Recommendation;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {
    private static final long ME = 9876543210L;
    private static final long OTHER = 9876543211L;

    @Mock
    private CartRepository orders;
    @Mock
    private ProductClient productClient;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(orders, productClient);
    }

    private Cart order(long phno, OrderStatus status, int... productIds) {
        Cart cart = new Cart();
        cart.setCustomerPhno(phno);
        cart.setStatus(status);
        List<OrderItem> items = new ArrayList<>();
        for (int id : productIds) {
            OrderItem item = new OrderItem();
            item.setProductId(id);
            item.setProductQuantity(1);
            items.add(item);
        }
        cart.setOrderItems(items);
        return cart;
    }

    private void catalog(int... inStockIds) {
        for (int id : inStockIds) {
            Product p = new Product();
            p.setProductId(id);
            p.setProductName("P" + id);
            p.setProductCategory("c");
            p.setProductPrice(10 * id);
            p.setProductStock(5);
            lenient().when(productClient.getProductById(id)).thenReturn(p);
        }
    }

    private List<Integer> ids(List<Recommendation> recs) {
        return recs.stream().map(Recommendation::productId).toList();
    }

    @Test
    void ranksProductsBoughtTogetherWithMyPurchasesFirstThenPopularOnesAndSkipsWhatIOwn() {
        // I bought 1. Others paired 1 with 2 (twice) and 3 (once); 4 is popular but never paired with 1.
        when(orders.findAll()).thenReturn(List.of(
                order(ME, OrderStatus.DELIVERED, 1),
                order(OTHER, OrderStatus.DELIVERED, 1, 2),
                order(OTHER, OrderStatus.PLACED, 1, 2, 3),
                order(OTHER, OrderStatus.DELIVERED, 4),
                order(OTHER, OrderStatus.DELIVERED, 4)));
        catalog(1, 2, 3, 4);

        List<Recommendation> recs = service.forCustomer(ME, 10);

        assertEquals(List.of(2, 3, 4), ids(recs));
        assertEquals("BOUGHT_WITH", recs.get(0).reason());
        assertEquals("BOUGHT_WITH", recs.get(1).reason());
        assertEquals("POPULAR", recs.get(2).reason());
    }

    @Test
    void aNewCustomerGetsBestSellersByUnits() {
        when(orders.findAll()).thenReturn(List.of(
                order(OTHER, OrderStatus.DELIVERED, 5),
                order(OTHER, OrderStatus.DELIVERED, 6),
                order(OTHER, OrderStatus.DELIVERED, 6)));
        catalog(5, 6);

        List<Recommendation> recs = service.forCustomer(ME, null);

        assertEquals(List.of(6, 5), ids(recs));
        assertTrue(recs.stream().allMatch(r -> r.reason().equals("POPULAR")));
    }

    @Test
    void cancelledAndUnpaidOrdersCountForNothing() {
        // My only order was cancelled, so I own nothing; the other cancelled and pending orders add no popularity.
        when(orders.findAll()).thenReturn(List.of(
                order(ME, OrderStatus.CANCELLED, 1),
                order(OTHER, OrderStatus.PENDING_PAYMENT, 2),
                order(OTHER, OrderStatus.CANCELLED, 3),
                order(OTHER, OrderStatus.DELIVERED, 4)));
        catalog(1, 2, 3, 4);

        assertEquals(List.of(4), ids(service.forCustomer(ME, 10)));
    }

    @Test
    void skipsSoldOutAndMissingProductsAndStillFillsTheLimit() {
        when(orders.findAll()).thenReturn(List.of(
                order(OTHER, OrderStatus.DELIVERED, 1, 1, 2, 3, 4)));
        Product soldOut = new Product();
        soldOut.setProductId(1);
        soldOut.setProductStock(0);
        lenient().when(productClient.getProductById(1)).thenReturn(soldOut);
        lenient().when(productClient.getProductById(2)).thenThrow(mock(FeignException.class));
        catalog(3, 4);

        assertEquals(List.of(3, 4), ids(service.forCustomer(ME, 2)));
    }

    @Test
    void limitIsCappedAndDefaulted() {
        List<Cart> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(order(OTHER, OrderStatus.DELIVERED, i));
        }
        when(orders.findAll()).thenReturn(many);
        int[] all = new int[30];
        for (int i = 0; i < 30; i++) all[i] = i + 1;
        catalog(all);

        assertEquals(8, service.forCustomer(ME, null).size());
        assertEquals(20, service.forCustomer(ME, 500).size());
        assertEquals(8, service.forCustomer(ME, 0).size());
    }

    @Test
    void rejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.forCustomer(123L, 5));
    }
}
