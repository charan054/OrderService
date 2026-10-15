package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOrderCsvServiceTest {
    private static final long CUSTOMER = 9876543210L;

    @Mock
    private CartRepository carts;
    @Mock
    private TrackingEventRepository tracking;
    @Mock
    private ProductClient productClient;

    private CustomerOrderCsvService service;

    @BeforeEach
    void setUp() {
        service = new CustomerOrderCsvService(carts, tracking, productClient);
    }

    private static OrderItem item(int productId, int qty, Double unit) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductQuantity(qty);
        i.setUnitPrice(unit);
        return i;
    }

    private static Cart order(long id, OrderItem... items) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(CUSTOMER);
        c.setOrderItems(List.of(items));
        c.setTotalPrice(250);
        return c;
    }

    private static Product product(String name) {
        Product p = new Product();
        p.setProductName(name);
        return p;
    }

    @Test
    void oneRowPerItemLineNewestOrderFirstWithPlacedDate() {
        when(carts.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(order(1, item(1, 1, 50.0)), order(2, item(1, 2, 100.0), item(2, 1, 50.0))));
        TrackingEvent placed = new TrackingEvent();
        placed.setTimestamp(Instant.parse("2026-10-14T10:00:00Z"));
        when(tracking.findByOrderIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(tracking.findByOrderIdOrderByTimestampAsc(2L)).thenReturn(List.of(placed));
        when(productClient.getProductById(1)).thenReturn(product("Soap"));
        when(productClient.getProductById(2)).thenReturn(product("Tea, green"));

        String[] lines = service.export(CUSTOMER).split("\r\n");

        assertEquals(4, lines.length);
        assertTrue(lines[1].startsWith("2,2026-10-14T10:00:00Z,PLACED,PHONEPE,false,Soap,2,100.0,200.0,"));
        assertTrue(lines[2].contains("\"Tea, green\",1,50.0,50.0"));
        assertTrue(lines[3].startsWith("1,,PLACED"));
    }

    @Test
    void aFormulaLikeProductNameCannotRunInASpreadsheet() {
        when(carts.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(order(1, item(1, 1, 50.0))));
        when(tracking.findByOrderIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(productClient.getProductById(1)).thenReturn(product("=HYPERLINK(\"http://evil\")"));

        assertTrue(service.export(CUSTOMER).contains(",\"'=HYPERLINK(\"\"http://evil\"\")\","));
    }

    @Test
    void aProductThatCannotBeLookedUpGetsAPlaceholderAndOldOrdersWithoutUnitPriceStillExport() {
        when(carts.findBycustomerPhno(CUSTOMER)).thenReturn(List.of(order(1, item(9, 3, null))));
        when(tracking.findByOrderIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(productClient.getProductById(9)).thenThrow(new RuntimeException("down"));

        String row = service.export(CUSTOMER).split("\r\n")[1];

        assertTrue(row.contains(",Product #9,3,,,"));
    }

    @Test
    void noOrdersGivesJustTheHeader() {
        when(carts.findBycustomerPhno(CUSTOMER)).thenReturn(List.of());
        assertEquals(CustomerOrderCsvService.HEADER, service.export(CUSTOMER));
    }

    @Test
    void anInvalidPhoneNumberIsRejected() {
        assertThrows(ProductException.class, () -> service.export(12345L));
    }
}
