package com.example.orderservice.client;

import feign.RequestInterceptor;
import org.springframework.stereotype.Component;

/**
 * Tells ProductService WHY a stock change is being made, so its stock ledger can say "sold on order #42" instead of
 * just "-2". The Feign call itself stays {@code updateProductStock(key, id, delta)}; the context rides along as query
 * parameters (type, reference, reason) added by {@link Interceptor} while a {@link #open scope} is active on the thread.
 * ProductService treats all of them as optional, so an older ProductService simply ignores them.
 */
public final class StockMovementContext {
    public static final String SALE = "SALE";
    public static final String CANCEL = "CANCEL";
    public static final String RETURN = "RETURN";

    private record Context(String type, String reference, String reason) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private StockMovementContext() {
    }

    /** Use in try-with-resources around the Feign call; restores whatever scope was active before. */
    public static Scope open(String type, String reference, String reason) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(type, reference, reason));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    @Component
    public static class Interceptor implements RequestInterceptor {
        @Override
        public void apply(feign.RequestTemplate template) {
            Context context = CURRENT.get();
            if (context == null || !template.path().endsWith("/product/updateStock")) {
                return;
            }
            template.query("type", context.type());
            if (context.reference() != null) {
                template.query("reference", context.reference());
            }
            if (context.reason() != null) {
                template.query("reason", context.reason());
            }
        }
    }
}
