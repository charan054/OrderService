package com.example.orderservice.service;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

/** A VAPID key pair (P-256) in the base64url form browsers and the Web Push library expect. */
public final class VapidKeys {
    public record Pair(String publicKey, String privateKey) {
    }

    private VapidKeys() {
    }

    public static Pair generate() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("prime256v1");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(spec, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            byte[] publicBytes = ((ECPublicKey) pair.getPublic()).getQ().getEncoded(false);    // 65-byte uncompressed point
            byte[] privateBytes = unsigned32(((ECPrivateKey) pair.getPrivate()).getD().toByteArray());
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            return new Pair(url.encodeToString(publicBytes), url.encodeToString(privateBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a VAPID key pair", e);
        }
    }

    private static byte[] unsigned32(byte[] bigEndian) {
        byte[] out = new byte[32];
        int copy = Math.min(bigEndian.length, 32);
        System.arraycopy(bigEndian, bigEndian.length - copy, out, 32 - copy, copy);
        return out;
    }
}
