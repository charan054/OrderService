package com.example.orderservice.service;

import com.example.orderservice.dto.LowStockAlertResult;
import com.example.orderservice.dto.LowStockItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LowStockAlertServiceTest {

    @Mock
    private OrderService orderService;
    @Mock
    private MailService mailService;

    private LowStockAlertService service(String to) {
        return new LowStockAlertService(orderService, mailService, to);
    }

    private LowStockItem item(int id, int stock) {
        return new LowStockItem(id, "item" + id, stock, 5, stock <= 0 ? "OUT" : "LOW", 2);
    }

    @Test
    void doesNothingWithoutAdminEmail() {
        LowStockAlertResult r = service("").run();
        assertFalse(r.sent());
        verify(orderService, never()).getLowStockReport();
    }

    @Test
    void emailsNewlyLowProductsOnlyOnce() {
        when(orderService.getLowStockReport()).thenReturn(List.of(item(1, 0), item(2, 3)));
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        LowStockAlertService s = service("owner@example.com");

        LowStockAlertResult first = s.run();
        LowStockAlertResult second = s.run();

        assertTrue(first.sent());
        assertEquals(2, first.products());
        assertFalse(second.sent());
        verify(mailService, times(1)).send(anyString(), anyString(), contains("OUT OF STOCK"));
    }

    @Test
    void alertsAgainAfterARecoveryAndNewDrop() {
        when(orderService.getLowStockReport())
                .thenReturn(List.of(item(1, 0)))
                .thenReturn(List.of())
                .thenReturn(List.of(item(1, 2)));
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
        LowStockAlertService s = service("owner@example.com");

        s.run();
        s.run();
        LowStockAlertResult third = s.run();

        assertTrue(third.sent());
        verify(mailService, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void failedSendIsRetriedNextRun() {
        when(orderService.getLowStockReport()).thenReturn(List.of(item(1, 0)));
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(false).thenReturn(true);
        LowStockAlertService s = service("owner@example.com");

        assertFalse(s.run().sent());
        assertTrue(s.run().sent());
    }
}
