package com.example.orderservice.service;

import com.example.orderservice.entity.PushSubscription;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerPushServiceTest {
    private static final long PHNO = 9876543210L;
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";

    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private PushSubscriptionRepository repo;
    private final List<String> sentPayloads = new ArrayList<>();
    private int status = 201;
    private CustomerPushService service;

    @BeforeEach
    void setUp() {
        repo = mock(PushSubscriptionRepository.class);
        PushSender fake = (subscription, payload) -> {
            sentPayloads.add(new String(payload, StandardCharsets.UTF_8));
            return status;
        };
        service = new CustomerPushService(repo, Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC),
                "public-key", fake, new DirectExecutor());
    }

    private static PushSubscription subscription(long id) {
        PushSubscription s = new PushSubscription();
        s.setId(id);
        s.setCustomerPhno(PHNO);
        s.setEndpoint(ENDPOINT + id);
        s.setP256dh("p256dh");
        s.setAuth("auth");
        return s;
    }

    @Test
    void withoutKeysTheFeatureIsOffAndNothingIsSent() {
        CustomerPushService off = new CustomerPushService(repo, Clock.systemUTC(), "", null, new DirectExecutor());

        assertFalse(off.isEnabled());
        off.notifyCustomer(PHNO, "t", "b", "/u");
        verify(repo, never()).findByCustomerPhno(PHNO);
        assertThrows(ProductException.class, () -> off.subscribe(PHNO, ENDPOINT, "k", "a"));
    }

    @Test
    void everySubscribedBrowserOfTheCustomerGetsTheMessageWithEscapedJson() {
        when(repo.findByCustomerPhno(PHNO)).thenReturn(List.of(subscription(1), subscription(2)));

        service.notifyCustomer(PHNO, "Order \"7\" shipped", "line1\nline2", "/shop.html");

        assertEquals(2, sentPayloads.size());
        assertEquals("{\"title\":\"Order \\\"7\\\" shipped\",\"body\":\"line1\\nline2\",\"url\":\"/shop.html\"}", sentPayloads.get(0));
    }

    @Test
    void aBrowserThatHasUnsubscribedIsForgotten() {
        PushSubscription gone = subscription(1);
        when(repo.findByCustomerPhno(PHNO)).thenReturn(List.of(gone));
        status = 410;

        service.notifyCustomer(PHNO, "t", "b", "/u");

        verify(repo).delete(gone);
    }

    @Test
    void aFailingPushNeverReachesTheCaller() {
        PushSender boom = (s, p) -> { throw new IllegalStateException("network down"); };
        CustomerPushService failing = new CustomerPushService(repo, Clock.systemUTC(), "public-key", boom, new DirectExecutor());
        when(repo.findByCustomerPhno(PHNO)).thenReturn(List.of(subscription(1)));

        failing.notifyCustomer(PHNO, "t", "b", "/u");
    }

    @Test
    void subscribingStoresTheBrowserAndRejectsBadOnes() {
        when(repo.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());
        when(repo.findByCustomerPhno(PHNO)).thenReturn(List.of());

        service.subscribe(PHNO, ENDPOINT, "p256dh", "auth");

        verify(repo).save(any(PushSubscription.class));
        assertThrows(ProductException.class, () -> service.subscribe(PHNO, "http://insecure.example.com/x", "p", "a"));
        assertThrows(ProductException.class, () -> service.subscribe(PHNO, ENDPOINT, " ", "a"));
    }

    @Test
    void onlyRealBrowserPushServicesAreAcceptedAsEndpoints() {
        org.junit.jupiter.api.Assertions.assertTrue(CustomerPushService.isPushServiceEndpoint("https://fcm.googleapis.com/fcm/send/x"));
        org.junit.jupiter.api.Assertions.assertTrue(CustomerPushService.isPushServiceEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x"));
        for (String bad : new String[]{"https://localhost/x", "https://127.0.0.1/x", "https://169.254.169.254/latest", "https://internal.example.com/x",
                "https://fcm.googleapis.com.evil.com/x", "https://evil.com/fcm.googleapis.com", "https://user@fcm.googleapis.com/x",
                "https://fcm.googleapis.com:8443/x", "http://fcm.googleapis.com/x", "not a url", ""}) {
            org.junit.jupiter.api.Assertions.assertFalse(CustomerPushService.isPushServiceEndpoint(bad), bad);
        }
    }

    @Test
    void aCustomerCannotSubscribeMoreThanTenBrowsers() {
        when(repo.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());
        List<PushSubscription> ten = new ArrayList<>();
        for (int i = 0; i < CustomerPushService.MAX_SUBSCRIPTIONS_PER_CUSTOMER; i++) {
            ten.add(subscription(i));
        }
        when(repo.findByCustomerPhno(PHNO)).thenReturn(ten);

        assertThrows(ProductException.class, () -> service.subscribe(PHNO, ENDPOINT, "p256dh", "auth"));
    }

    @Test
    void vapidKeysHaveTheShapeBrowsersExpect() {
        VapidKeys.Pair pair = VapidKeys.generate();

        byte[] publicBytes = Base64.getUrlDecoder().decode(pair.publicKey());
        assertEquals(65, publicBytes.length);
        assertEquals(4, publicBytes[0]);                                    // uncompressed point marker
        assertEquals(32, Base64.getUrlDecoder().decode(pair.privateKey()).length);
        assertTrue(!pair.publicKey().equals(VapidKeys.generate().publicKey()));
    }

    @Test
    void theRealSenderAcceptsAFreshlyGeneratedKeyPair() throws Exception {
        VapidKeys.Pair pair = VapidKeys.generate();

        CustomerPushService real = new CustomerPushService(repo, Clock.systemUTC(), pair.publicKey(), pair.privateKey(), "mailto:shop@example.com");

        assertTrue(real.isEnabled());
        assertEquals(pair.publicKey(), real.publicKey());
    }
}
