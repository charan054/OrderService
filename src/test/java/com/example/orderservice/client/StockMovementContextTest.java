package com.example.orderservice.client;

import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockMovementContextTest {
    private final StockMovementContext.Interceptor interceptor = new StockMovementContext.Interceptor();

    private RequestTemplate stockCall() {
        return new RequestTemplate().method(feign.Request.HttpMethod.PUT).uri("/product/updateStock");
    }

    private static String first(RequestTemplate template, String name) {
        Collection<String> values = template.queries().get(name);
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    @Test
    void anOpenScopeAddsTypeReferenceAndReasonToTheStockCall() {
        RequestTemplate template = stockCall();
        try (StockMovementContext.Scope ignored = StockMovementContext.open("SALE", "order 42", "Awaiting UPI payment")) {
            interceptor.apply(template);
        }
        assertEquals("SALE", first(template, "type"));
        assertEquals("order%2042", first(template, "reference"));
        assertEquals("Awaiting%20UPI%20payment", first(template, "reason"));
    }

    @Test
    void aMissingReasonIsLeftOut() {
        RequestTemplate template = stockCall();
        try (StockMovementContext.Scope ignored = StockMovementContext.open("CANCEL", "order 7", null)) {
            interceptor.apply(template);
        }
        assertEquals("CANCEL", first(template, "type"));
        assertFalse(template.queries().containsKey("reason"));
    }

    @Test
    void withoutAScopeOrForAnotherEndpointNothingIsAdded() {
        RequestTemplate noScope = stockCall();
        interceptor.apply(noScope);
        assertTrue(noScope.queries().isEmpty());

        RequestTemplate other = new RequestTemplate().method(feign.Request.HttpMethod.GET).uri("/product/byId");
        try (StockMovementContext.Scope ignored = StockMovementContext.open("SALE", "order 1", null)) {
            interceptor.apply(other);
        }
        assertTrue(other.queries().isEmpty());
    }

    @Test
    void closingAScopeRestoresTheOneThatWasActiveBefore() {
        try (StockMovementContext.Scope outer = StockMovementContext.open("SALE", "order 1", null)) {
            try (StockMovementContext.Scope inner = StockMovementContext.open("CANCEL", "order 2", null)) {
                RequestTemplate t = stockCall();
                interceptor.apply(t);
                assertEquals("CANCEL", first(t, "type"));
            }
            RequestTemplate t = stockCall();
            interceptor.apply(t);
            assertEquals("SALE", first(t, "type"));
        }
        RequestTemplate after = stockCall();
        interceptor.apply(after);
        assertTrue(after.queries().isEmpty());
    }
}
