package com.example.orderservice.service;

import com.example.orderservice.exception.ProductException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Which URLs a webhook may point at: https only, and never an address inside this network (loopback, private,
 * link-local such as a cloud metadata service) - otherwise registering a webhook would let someone make this
 * server call internal services. Checked when registering and again just before each send, because a host name
 * can be re-pointed later.
 */
final class WebhookUrls {
    static final int MAX_LENGTH = 500;

    private WebhookUrls() {
    }

    /** Returns the URL trimmed, or throws ProductException saying why it is not allowed. */
    static String validate(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            throw new ProductException("A webhook URL is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw new ProductException("The URL can be at most " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ProductException("That is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ProductException("The webhook URL must start with https://");
        }
        if (uri.getUserInfo() != null) {
            throw new ProductException("The URL must not contain a username or password");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalV6(address)) {
                    throw new ProductException("The URL points at a private or internal address");
                }
            }
        } catch (UnknownHostException e) {
            throw new ProductException("Could not find that host");
        }
        return url;
    }

    // fc00::/7 - IPv6's private range, which isSiteLocalAddress() does not cover
    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
