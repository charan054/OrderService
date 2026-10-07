package com.example.orderservice.service;

import com.example.orderservice.dto.CustomerInsights;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerInsightsServiceTest {
    @Mock
    private CartRepository orders;

    private CustomerInsightsService service;

    @BeforeEach
    void setUp() {
        service = new CustomerInsightsService(orders);
    }

    private Cart order(long id, long phno, String name, OrderStatus status, double total, double refunded) {
        Cart c = new Cart();
        c.setOrderId(id);
        c.setCustomerPhno(phno);
        c.setCustomerName(name);
        c.setStatus(status);
        c.setTotalPrice(total);
        c.setRefundedAmount(refunded);
        return c;
    }

    @Test
    void separatesNewFromRepeatCustomersAndIgnoresOrdersThatDoNotStand() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, 9000000001L, "Ann", OrderStatus.DELIVERED, 500, 100),
                order(2, 9000000001L, "Ann B", OrderStatus.PLACED, 300, 0),
                order(3, 9000000002L, "Bob", OrderStatus.SHIPPED, 200, 0),
                order(4, 9000000003L, "Cy", OrderStatus.CANCELLED, 999, 999),
                order(5, 9000000003L, "Cy", OrderStatus.RETURNED, 400, 400),
                order(6, 9000000004L, "Di", OrderStatus.PENDING_PAYMENT, 50, 0)));

        CustomerInsights r = service.insights(10);

        assertEquals(2, r.totalCustomers());
        assertEquals(1, r.repeatCustomers());
        assertEquals(1, r.newCustomers());
        assertEquals(50.0, r.repeatRate());
        assertEquals(3, r.totalOrders());
        assertEquals(900.0, r.netRevenue());
        assertEquals(300.0, r.averageOrderValue());
        assertEquals(1.5, r.averageOrdersPerCustomer());
    }

    @Test
    void topCustomersAreRankedByNetSpendWithTheirLatestName() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, 9000000001L, "Ann", OrderStatus.DELIVERED, 500, 100),
                order(2, 9000000001L, "Ann B", OrderStatus.PLACED, 300, 0),
                order(3, 9000000002L, "Bob", OrderStatus.SHIPPED, 1000, 0),
                order(4, 9000000005L, "Eve", OrderStatus.SHIPPED, 50, 0)));

        List<CustomerInsights.TopCustomer> top = service.insights(2).topCustomers();

        assertEquals(2, top.size());
        assertEquals(9000000002L, top.get(0).phno());
        assertEquals(1000.0, top.get(0).netSpend());
        assertEquals(9000000001L, top.get(1).phno());
        assertEquals("Ann B", top.get(1).name());
        assertEquals(700.0, top.get(1).netSpend());
        assertEquals(2, top.get(1).orders());
    }

    @Test
    void noOrdersGivesNullAveragesNotDivideByZero() {
        when(orders.findAll()).thenReturn(List.of());

        CustomerInsights r = service.insights(10);

        assertEquals(0, r.totalCustomers());
        assertNull(r.repeatRate());
        assertNull(r.averageOrderValue());
        assertNull(r.averageOrdersPerCustomer());
        assertEquals(0, r.topCustomers().size());
    }

    @Test
    void ordersWithNoStatusAreSkippedNotCrashed() {
        when(orders.findAll()).thenReturn(List.of(
                order(1, 9000000001L, "Ann", null, 500, 0),
                order(2, 9000000001L, "Ann", OrderStatus.PLACED, 100, 0)));

        assertEquals(1, service.insights(10).totalOrders());
    }

    @Test
    void topMustBeInRange() {
        assertThrows(ProductException.class, () -> service.insights(0));
        assertThrows(ProductException.class, () -> service.insights(51));
    }
}
