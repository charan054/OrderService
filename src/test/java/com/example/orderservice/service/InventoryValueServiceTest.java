package com.example.orderservice.service;

import com.example.orderservice.dto.InventoryValue;
import com.example.orderservice.dto.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryValueServiceTest {
    private static Product product(String category, double price, int stock) {
        Product p = new Product();
        p.setProductCategory(category);
        p.setProductPrice(price);
        p.setProductStock(stock);
        return p;
    }

    @Test
    void valuesStockAtSellingPriceAndGroupsCategoriesIgnoringCase() {
        InventoryValue v = InventoryValueService.summarise(List.of(
                product("Snacks", 10.5, 4), product("snacks", 20, 1), product("Soap", 45, 2), product("Soap", 99, 0)));

        assertEquals(4, v.products());
        assertEquals(7, v.units());
        assertEquals(152.0, v.totalValue());
        assertEquals(2, v.categories().size());
        assertEquals("Soap", v.categories().get(0).category());
        assertEquals(90.0, v.categories().get(0).value());
        assertEquals(2, v.categories().get(0).products());
        assertEquals(62.0, v.categories().get(1).value());
    }

    @Test
    void negativeStockBlankCategoriesAndAnEmptyCatalogAreHandled() {
        InventoryValue v = InventoryValueService.summarise(List.of(product(null, 10, -3), product("  ", 5, 2)));
        assertEquals(1, v.categories().size());
        assertEquals("Uncategorised", v.categories().get(0).category());
        assertEquals(10.0, v.totalValue());

        InventoryValue empty = InventoryValueService.summarise(List.of());
        assertEquals(0, empty.products());
        assertEquals(0.0, empty.totalValue());
    }
}
