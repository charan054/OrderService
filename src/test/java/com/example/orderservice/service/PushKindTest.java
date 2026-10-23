package com.example.orderservice.service;

import com.example.orderservice.entity.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushKindTest {
    @Test
    void anOrderStatusBelongsToOneKind() {
        assertEquals(PushKind.PLACED, PushKind.of(OrderStatus.PLACED));
        assertEquals(PushKind.DELIVERY, PushKind.of(OrderStatus.SHIPPED));
        assertEquals(PushKind.DELIVERY, PushKind.of(OrderStatus.DELIVERED));
        assertEquals(PushKind.CHANGES, PushKind.of(OrderStatus.CANCELLED));
        assertEquals(PushKind.CHANGES, PushKind.of(OrderStatus.RETURNED));
    }

    @Test
    void mutedKindsRoundTripAndNothingMutedStoresNull() {
        Set<PushKind> muted = EnumSet.of(PushKind.CHANGES, PushKind.PLACED);

        String stored = PushKind.serialize(muted);

        assertEquals("placed,changes", stored);
        assertEquals(muted, PushKind.parse(stored));
        assertNull(PushKind.serialize(EnumSet.noneOf(PushKind.class)));
        assertTrue(PushKind.parse(null).isEmpty());
        assertTrue(PushKind.parse(" , bogus ").isEmpty());
    }

    @Test
    void isMutedLooksAtTheKindOfTheStatus() {
        assertTrue(PushKind.isMuted("delivery", OrderStatus.SHIPPED));
        assertFalse(PushKind.isMuted("delivery", OrderStatus.PLACED));
        assertFalse(PushKind.isMuted(null, OrderStatus.SHIPPED));
    }
}
