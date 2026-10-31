package com.example.orderservice.service;

import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.WebhookDelivery;
import com.example.orderservice.entity.WebhookEndpoint;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.WebhookDeliveryRepository;
import com.example.orderservice.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    // a literal public address, so validating it needs no DNS lookup
    private static final String PUBLIC_URL = "https://93.184.216.34/hooks/orders";

    /** Records what was sent and answers with whatever the test sets. */
    static class FakeSender implements WebhookSender {
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        int status = 200;
        IOException failure;

        @Override
        public int post(String url, String body, Map<String, String> h) throws IOException {
            urls.add(url);
            bodies.add(body);
            headers.add(h);
            if (failure != null) {
                throw failure;
            }
            return status;
        }
    }

    private WebhookEndpointRepository endpoints;
    private WebhookDeliveryRepository deliveries;
    private FakeSender sender;
    private WebhookService service;

    @BeforeEach
    void setUp() {
        endpoints = mock(WebhookEndpointRepository.class);
        deliveries = mock(WebhookDeliveryRepository.class);
        sender = new FakeSender();
        service = new WebhookService(endpoints, deliveries, sender, Clock.fixed(NOW, ZoneOffset.UTC));
        when(endpoints.save(any(WebhookEndpoint.class))).thenAnswer(i -> {
            WebhookEndpoint e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(deliveries.save(any(WebhookDelivery.class))).thenAnswer(i -> i.getArgument(0));
    }

    private WebhookEndpoint endpoint(long id) {
        WebhookEndpoint e = new WebhookEndpoint();
        e.setId(id);
        e.setUrl(PUBLIC_URL);
        e.setSecret("whsec_test");
        e.setActive(true);
        when(endpoints.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    private WebhookDelivery pending(long id, long endpointId) {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(id);
        d.setEndpointId(endpointId);
        d.setOrderId(42);
        d.setStatus("SHIPPED");
        d.setOccurredAt(NOW.minusSeconds(30));
        d.setNextAttemptAt(NOW.minusSeconds(1));
        when(deliveries.findByStateAndNextAttemptAtLessThanEqualOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq("PENDING"), any(Instant.class), any())).thenReturn(List.of(d));
        return d;
    }

    // ---- registering ----

    @Test
    void registeringStoresTheUrlWithAFreshSecretAndReturnsItOnce() {
        WebhookService.Registered registered = service.register("  " + PUBLIC_URL + " ");

        assertEquals(PUBLIC_URL, registered.url());
        assertTrue(registered.secret().startsWith("whsec_"));
        assertTrue(registered.secret().length() > 40);
        assertNotNull(registered.id());
        assertNotNull(service.register(PUBLIC_URL).secret());
    }

    @Test
    void twoRegistrationsGetDifferentSecrets() {
        assertFalse(service.register(PUBLIC_URL).secret().equals(service.register(PUBLIC_URL).secret()));
    }

    @Test
    void onlyPublicHttpsUrlsAreAccepted() {
        for (String bad : new String[]{null, "", "  ", "http://93.184.216.34/x", "ftp://93.184.216.34/x", "not a url",
                "https://127.0.0.1/x", "https://localhost/x", "https://10.0.0.5/x", "https://192.168.1.10/x",
                "https://172.16.0.1/x", "https://169.254.169.254/latest/meta-data", "https://[::1]/x",
                "https://[fd00::1]/x", "https://user:pass@93.184.216.34/x", "https://0.0.0.0/x"}) {
            assertThrows(ProductException.class, () -> service.register(bad), String.valueOf(bad));
        }
        verify(endpoints, never()).save(any());
    }

    @Test
    void anOverlongUrlIsRefused() {
        assertThrows(ProductException.class, () -> service.register("https://93.184.216.34/" + "a".repeat(500)));
    }

    @Test
    void thereIsALimitOnHowManyCanBeRegistered() {
        when(endpoints.count()).thenReturn((long) WebhookService.MAX_ENDPOINTS);

        assertThrows(ProductException.class, () -> service.register(PUBLIC_URL));
        verify(endpoints, never()).save(any());
    }

    @Test
    void anUnknownWebhookIsNotFoundWhetherDeletedSwitchedOrListed() {
        when(endpoints.existsById(9L)).thenReturn(false);
        when(endpoints.findById(9L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> service.delete(9));
        assertThrows(OrderNotFoundException.class, () -> service.setActive(9, true));
        assertThrows(OrderNotFoundException.class, () -> service.recentDeliveries(9));
    }

    @Test
    void deletingRemovesTheEndpointAndItsQueuedDeliveries() {
        when(endpoints.existsById(3L)).thenReturn(true);

        service.delete(3);

        verify(deliveries).deleteByEndpointId(3L);
        verify(endpoints).deleteById(3L);
    }

    @Test
    void switchingAnEndpointBackOnClearsItsFailureCount() {
        WebhookEndpoint e = endpoint(4);
        e.setActive(false);
        e.setFailedStreak(10);

        WebhookEndpoint result = service.setActive(4, true);

        assertTrue(result.isActive());
        assertEquals(0, result.getFailedStreak());
    }

    // ---- queueing ----

    @Test
    void aStatusChangeQueuesOneDeliveryPerActiveEndpoint() {
        WebhookEndpoint a = endpoint(1);
        WebhookEndpoint b = endpoint(2);
        when(endpoints.findByActiveTrue()).thenReturn(List.of(a, b));

        service.onStatusChanged(new OrderStatusChangedEvent(42, OrderStatus.SHIPPED, NOW));

        org.mockito.ArgumentCaptor<WebhookDelivery> saved = org.mockito.ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveries, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(List.of(1L, 2L), saved.getAllValues().stream().map(WebhookDelivery::getEndpointId).toList());
        WebhookDelivery first = saved.getAllValues().get(0);
        assertEquals(42L, first.getOrderId());
        assertEquals("SHIPPED", first.getStatus());
        assertEquals(WebhookDelivery.PENDING, first.getState());
        assertEquals(NOW, first.getNextAttemptAt());
    }

    @Test
    void withNoEndpointsNothingIsQueued() {
        when(endpoints.findByActiveTrue()).thenReturn(List.of());

        service.onStatusChanged(new OrderStatusChangedEvent(42, OrderStatus.PLACED, NOW));

        verify(deliveries, never()).save(any());
    }

    @Test
    void aFailureToQueueNeverReachesTheOrderThatTriggeredIt() {
        when(endpoints.findByActiveTrue()).thenThrow(new IllegalStateException("database down"));

        service.onStatusChanged(new OrderStatusChangedEvent(42, OrderStatus.PLACED, NOW));   // must not throw
    }

    // ---- sending ----

    @Test
    void aDueDeliveryIsPostedSignedAndMarkedDelivered() {
        endpoint(1);
        WebhookDelivery d = pending(77, 1);

        assertEquals(1, service.dispatchDue());

        assertEquals(List.of(PUBLIC_URL), sender.urls);
        String body = sender.bodies.get(0);
        assertTrue(body.contains("\"orderId\":42"), body);
        assertTrue(body.contains("\"status\":\"SHIPPED\""), body);
        assertTrue(body.contains("\"type\":\"order.status_changed\""), body);
        Map<String, String> headers = sender.headers.get(0);
        assertEquals("77", headers.get("X-Webhook-Id"));
        String timestamp = String.valueOf(NOW.getEpochSecond());
        assertEquals(timestamp, headers.get("X-Webhook-Timestamp"));
        assertEquals(WebhookService.sign("whsec_test", timestamp, body), headers.get("X-Webhook-Signature"));
        assertEquals(WebhookDelivery.DELIVERED, d.getState());
        assertEquals(1, d.getAttempts());
        assertEquals(NOW, d.getDeliveredAt());
        assertNull(d.getNextAttemptAt());
    }

    @Test
    void theSignatureDependsOnTheSecretTheTimestampAndTheBody() {
        String base = WebhookService.sign("secret", "100", "{}");

        assertTrue(base.matches("sha256=[0-9a-f]{64}"));
        assertEquals(base, WebhookService.sign("secret", "100", "{}"));
        assertFalse(base.equals(WebhookService.sign("other", "100", "{}")));
        assertFalse(base.equals(WebhookService.sign("secret", "101", "{}")));
        assertFalse(base.equals(WebhookService.sign("secret", "100", "{ }")));
    }

    @Test
    void aFailedAttemptIsRetriedLaterWithBackingOffDelays() {
        endpoint(1);
        WebhookDelivery d = pending(77, 1);
        sender.status = 500;
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
                Duration.ofHours(12)};

        for (int attempt = 1; attempt < WebhookService.MAX_ATTEMPTS; attempt++) {
            service.dispatchDue();

            assertEquals(WebhookDelivery.PENDING, d.getState());
            assertEquals(attempt, d.getAttempts());
            assertEquals(NOW.plus(expected[attempt - 1]), d.getNextAttemptAt());
            assertEquals("Receiver answered HTTP 500", d.getLastError());
        }
    }

    @Test
    void aConnectionErrorCountsAsAFailedAttempt() {
        endpoint(1);
        WebhookDelivery d = pending(77, 1);
        sender.failure = new IOException("connect timed out");

        service.dispatchDue();

        assertEquals(WebhookDelivery.PENDING, d.getState());
        assertEquals("connect timed out", d.getLastError());
    }

    @Test
    void afterTheLastAttemptTheDeliveryGivesUpAndCountsAgainstTheEndpoint() {
        WebhookEndpoint e = endpoint(1);
        WebhookDelivery d = pending(77, 1);
        d.setAttempts(WebhookService.MAX_ATTEMPTS - 1);
        sender.status = 503;

        service.dispatchDue();

        assertEquals(WebhookDelivery.FAILED, d.getState());
        assertEquals(WebhookService.MAX_ATTEMPTS, d.getAttempts());
        assertNull(d.getNextAttemptAt());
        assertEquals(1, e.getFailedStreak());
        assertTrue(e.isActive());
    }

    @Test
    void anEndpointThatKeepsFailingIsSwitchedOff() {
        WebhookEndpoint e = endpoint(1);
        e.setFailedStreak(WebhookService.DISABLE_AFTER - 1);
        WebhookDelivery d = pending(77, 1);
        d.setAttempts(WebhookService.MAX_ATTEMPTS - 1);
        sender.status = 502;

        service.dispatchDue();

        assertFalse(e.isActive());
    }

    @Test
    void aSuccessClearsTheFailureCount() {
        WebhookEndpoint e = endpoint(1);
        e.setFailedStreak(4);
        pending(77, 1);

        service.dispatchDue();

        assertEquals(0, e.getFailedStreak());
    }

    @Test
    void aDeliveryForARemovedOrSwitchedOffEndpointIsDroppedWithoutSending() {
        when(endpoints.findById(anyLong())).thenReturn(Optional.empty());
        WebhookDelivery gone = pending(77, 1);

        service.dispatchDue();

        assertEquals(WebhookDelivery.FAILED, gone.getState());
        assertTrue(sender.urls.isEmpty());

        WebhookEndpoint off = endpoint(2);
        off.setActive(false);
        WebhookDelivery queued = pending(78, 2);

        service.dispatchDue();

        assertEquals(WebhookDelivery.FAILED, queued.getState());
        assertTrue(sender.urls.isEmpty());
    }

    @Test
    void anEndpointThatNowResolvesToAnInternalAddressIsNotCalled() {
        WebhookEndpoint e = endpoint(1);
        e.setUrl("https://10.1.2.3/hooks");
        WebhookDelivery d = pending(77, 1);

        service.dispatchDue();

        assertTrue(sender.urls.isEmpty());
        assertEquals(WebhookDelivery.PENDING, d.getState());
        assertEquals("The URL points at a private or internal address", d.getLastError());
    }

    @Test
    void oldFinishedDeliveriesAreTidiedAfterEachRound() {
        when(deliveries.findByStateAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any(), any())).thenReturn(List.of());

        service.dispatchDue();

        verify(deliveries).deleteFinishedBefore(NOW.minus(WebhookService.KEEP_FINISHED));
    }
}
