package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.MarkdownResult;
import com.example.orderservice.dto.Product;
import com.example.orderservice.exception.ProductException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlowMoverMarkdownTest {
    @Mock
    private ProductClient productClient;
    private SlowMoverService service;

    @BeforeEach
    void setUp() {
        service = new SlowMoverService(null, null, null, Clock.systemUTC(), productClient, "key");
    }

    private Product product(double price) {
        Product p = new Product();
        p.setProductId(7);
        p.setProductName("Oats");
        p.setProductPrice(price);
        return p;
    }

    @Test
    void marksDownByPercentOfTheCurrentPriceAndUpdatesTheCatalog() {
        when(productClient.getProductById(7)).thenReturn(product(250.0));

        MarkdownResult result = service.markdown(7, 10);

        assertEquals(225.0, result.newPrice());
        assertEquals(250.0, result.oldPrice());
        verify(productClient).updateProductPrice("key", 7, 225.0);
    }

    @Test
    void roundsToThePaisaAndNeverGoesBelowOneRupee() {
        assertEquals(33.49, SlowMoverService.newPrice(49.99, 33), 0.001);
        assertEquals(1.0, SlowMoverService.newPrice(1.2, 70), 0.001);
    }

    @Test
    void rejectsOutOfRangePercentsAndMarkdownsThatChangeNothing() {
        assertThrows(ProductException.class, () -> service.markdown(7, 0));
        assertThrows(ProductException.class, () -> service.markdown(7, 71));

        when(productClient.getProductById(7)).thenReturn(product(1.0));
        assertThrows(ProductException.class, () -> service.markdown(7, 10));
        verify(productClient, never()).updateProductPrice(any(), anyInt(), anyDouble());
    }
}
