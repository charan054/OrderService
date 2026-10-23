package com.example.orderservice.service;

import com.example.orderservice.entity.PushSubscription;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/** The real sender: VAPID-signed Web Push through nl.martijndwars:web-push. */
public class WebPushSender implements PushSender {
    private final nl.martijndwars.webpush.PushService service;

    public WebPushSender(String publicKey, String privateKey, String subject) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.service = new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
    }

    @Override
    public int send(PushSubscription subscription, byte[] payload) throws Exception {
        Notification notification = new Notification(subscription.getEndpoint(), subscription.getP256dh(), subscription.getAuth(), payload);
        return service.send(notification).getStatusLine().getStatusCode();
    }
}
