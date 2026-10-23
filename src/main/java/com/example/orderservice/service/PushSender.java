package com.example.orderservice.service;

import com.example.orderservice.entity.PushSubscription;

/** Delivers one payload to one browser; returns the push service's HTTP status (201 ok, 404/410 = the browser unsubscribed). */
public interface PushSender {
    int send(PushSubscription subscription, byte[] payload) throws Exception;
}
