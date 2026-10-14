package com.example.orderservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cheap guards for the things that quietly broke the static pages on phones and screen readers: a page without a
 * viewport tag renders at desktop width on a phone, and the storefront's add-to-cart controls must keep a spoken name
 * and the stock/whole-number limits. (A real browser run would be better, but there is no Node/Playwright here.)
 */
class StaticPageBasicsTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void everyPageHasLanguageTitleAndViewport() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(STATIC)) {
            for (Path page : files.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
                String head = Files.readString(page, StandardCharsets.UTF_8);
                head = head.substring(0, Math.min(head.length(), 3000)).toLowerCase();
                if (!head.contains("<html lang=")) problems.add(page.getFileName() + ": missing <html lang>");
                if (!head.contains("<title>")) problems.add(page.getFileName() + ": missing <title>");
                if (!head.contains("name=\"viewport\"")) problems.add(page.getFileName() + ": missing viewport meta");
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void storefrontKeepsAddToCartLimitsAndLabels() throws IOException {
        String shop = Files.readString(STATIC.resolve("shop.html"), StandardCharsets.UTF_8);
        assertTrue(shop.contains("aria-label=\"Add ${escapeHtml(p.productName)} to cart\""), "Add button lost its accessible name");
        assertTrue(shop.contains("max=\"${p.productStock}\" step=\"1\""), "quantity input lost its stock cap / whole-number step");
        assertTrue(shop.contains("Math.floor(Number(qtyInput.value))"), "addToCart no longer rounds to whole units");
        assertTrue(shop.contains("product.productStock - (existing ? existing.quantity : 0)"), "addToCart no longer caps at stock");
        assertTrue(shop.contains("function syncCardSteppers()"), "product cards lost their quick-add stepper");
    }
}
