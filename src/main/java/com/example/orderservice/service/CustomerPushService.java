package com.example.orderservice.service;

import com.example.orderservice.entity.PushSubscription;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browser notifications (Web Push) for customers who allowed them: order shipped, out for delivery, delivered, cancelled...
 * It rides on the same events as the order emails. Needs a VAPID key pair (push.vapid.public-key / private-key; a fresh pair
 * comes from GET /cart/push/new-keys); without one the feature is simply off and nothing changes. Sending is best effort on a
 * background thread - it never slows or fails an order change - and a browser that has unsubscribed (404/410) is forgotten.
 */
@Service
public class CustomerPushService {
    private static final Logger log = LoggerFactory.getLogger(CustomerPushService.class);
    static final int MAX_SUBSCRIPTIONS_PER_CUSTOMER = 10;
    // Chrome/Edge/Brave (FCM), Firefox, Safari and Windows push services.
    private static final List<String> ALLOWED_PUSH_HOSTS = List.of("fcm.googleapis.com", "push.services.mozilla.com",
            "push.apple.com", "notify.windows.com");

    private final PushSubscriptionRepository subscriptions;
    private final Clock clock;
    private final String publicKey;
    private final PushSender sender;
    private final ExecutorService executor;

    @Autowired
    public CustomerPushService(PushSubscriptionRepository subscriptions, Clock clock,
                               @Value("${push.vapid.public-key:}") String publicKey,
                               @Value("${push.vapid.private-key:}") String privateKey,
                               @Value("${push.vapid.subject:mailto:admin@example.com}") String subject) {
        this(subscriptions, clock, publicKey, buildSender(publicKey, privateKey, subject), Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "customer-push");
            t.setDaemon(true);
            return t;
        }));
    }

    // Tests pass a fake sender and a same-thread executor.
    CustomerPushService(PushSubscriptionRepository subscriptions, Clock clock, String publicKey, PushSender sender, ExecutorService executor) {
        this.subscriptions = subscriptions;
        this.clock = clock;
        this.publicKey = publicKey == null ? "" : publicKey;
        this.sender = sender;
        this.executor = executor;
    }

    private static PushSender buildSender(String publicKey, String privateKey, String subject) {
        if (publicKey == null || publicKey.isBlank() || privateKey == null || privateKey.isBlank()) {
            return null;
        }
        try {
            return new WebPushSender(publicKey, privateKey, subject);
        } catch (Exception e) {
            log.error("Browser notifications are off: the VAPID keys could not be used: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The server posts to the endpoint a browser hands over, so only the real browser push services are accepted - anything
     * else (an internal address, an arbitrary website) would let a customer aim this server at it.
     */
    static boolean isPushServiceEndpoint(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(endpoint);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String allowed : ALLOWED_PUSH_HOSTS) {
            if (h.equals(allowed) || h.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnabled() {
        return sender != null && !publicKey.isBlank();
    }

    public String publicKey() {
        return publicKey;
    }

    /** Remembers a browser for this customer (a browser that subscribes again simply moves to the latest customer who used it). */
    @Transactional
    public void subscribe(long phno, String endpoint, String p256dh, String auth) {
        if (!isEnabled()) {
            throw new ProductException("Browser notifications are not set up on this shop yet");
        }
        if (!isPushServiceEndpoint(endpoint) || endpoint.length() > 500
                || p256dh == null || p256dh.isBlank() || p256dh.length() > 200 || auth == null || auth.isBlank() || auth.length() > 100) {
            throw new ProductException("That browser subscription is not valid");
        }
        PushSubscription s = subscriptions.findByEndpoint(endpoint).orElseGet(PushSubscription::new);
        if (s.getId() == null && subscriptions.findByCustomerPhno(phno).size() >= MAX_SUBSCRIPTIONS_PER_CUSTOMER) {
            throw new ProductException("Too many browsers are subscribed - turn some off first");
        }
        s.setCustomerPhno(phno);
        s.setEndpoint(endpoint);
        s.setP256dh(p256dh);
        s.setAuth(auth);
        s.setCreatedAt(clock.instant());
        subscriptions.save(s);
    }

    @Transactional
    public void unsubscribe(long phno, String endpoint) {
        subscriptions.deleteByEndpointAndCustomerPhno(endpoint, phno);
    }

    /** Tells every subscribed browser of this customer. Never throws. */
    public void notifyCustomer(long phno, String title, String body, String url) {
        if (!isEnabled()) {
            return;
        }
        try {
            List<PushSubscription> targets = subscriptions.findByCustomerPhno(phno);
            if (targets.isEmpty()) {
                return;
            }
            byte[] payload = ("{\"title\":" + quote(title) + ",\"body\":" + quote(body) + ",\"url\":" + quote(url) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            for (PushSubscription target : targets) {
                executor.execute(() -> deliver(target, payload));
            }
        } catch (RuntimeException e) {
            log.warn("Could not queue browser notifications for {}: {}", phno, e.getMessage());
        }
    }

    private void deliver(PushSubscription target, byte[] payload) {
        try {
            int status = sender.send(target, payload);
            if (status == 404 || status == 410) {
                subscriptions.delete(target);          // the browser unsubscribed or was uninstalled
            } else if (status >= 400) {
                log.warn("Push to subscription {} was refused with HTTP {}", target.getId(), status);
            }
        } catch (Exception e) {
            log.warn("Push to subscription {} failed: {}", target.getId(), e.getMessage());
        }
    }

    static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
