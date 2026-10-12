package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.MarginCheck;
import com.example.orderservice.dto.ProductCost;
import com.example.orderservice.exception.ProductException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponMarginServiceTest {
    private final ProductClient products = mock(ProductClient.class);
    private final CouponMarginService service = new CouponMarginService(products, "key");

    @Test
    void listsOnlyProductsThatWouldSellBelowCostWorstFirst() {
        when(products.getCosts("key")).thenReturn(List.of(
                new ProductCost(1, "Soap", 100, 85),     // 20% off -> 80, loses 5
                new ProductCost(2, "Dal", 100, 60),      // 20% off -> 80, fine
                new ProductCost(3, "Ghee", 500, 450)));  // 20% off -> 400, loses 50

        MarginCheck check = service.check(20);

        assertEquals(3, check.productsWithCost());
        assertEquals(2, check.atLoss().size());
        assertEquals("Ghee", check.atLoss().get(0).productName());
        assertEquals(50.0, check.atLoss().get(0).lossPerUnit());
        assertEquals(80.0, check.atLoss().get(1).priceAfterDiscount());
    }

    @Test
    void sellingExactlyAtCostIsNotAWarning() {
        when(products.getCosts("key")).thenReturn(List.of(new ProductCost(1, "Soap", 100, 80)));

        assertEquals(0, service.check(20).atLoss().size());
    }

    @Test
    void noCostsMeansNothingWasChecked() {
        when(products.getCosts("key")).thenReturn(List.of());

        MarginCheck check = service.check(10);

        assertEquals(0, check.productsWithCost());
        assertEquals(0, check.atLoss().size());
    }

    @Test
    void aPercentOutsideZeroToHundredIsRejected() {
        assertThrows(ProductException.class, () -> service.check(-1));
        assertThrows(ProductException.class, () -> service.check(101));
        assertThrows(ProductException.class, () -> service.check(Double.NaN));
    }
}
