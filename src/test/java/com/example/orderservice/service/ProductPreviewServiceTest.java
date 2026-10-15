package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPreviewServiceTest {
    @Mock
    private ProductClient productClient;

    private ProductPreviewService service;

    @BeforeEach
    void setUp() {
        service = new ProductPreviewService(productClient);
    }

    private static Product product(String name, double price, int stock, String image) {
        Product p = new Product();
        p.setProductId(7);
        p.setProductName(name);
        p.setProductCategory("facewash");
        p.setProductPrice(price);
        p.setProductStock(stock);
        p.setProductImageUrl(image);
        return p;
    }

    @Test
    void pageCarriesTheLinkCardTagsAndSendsVisitorsOnToTheStorefront() {
        when(productClient.getProductById(7)).thenReturn(product("Cetaphil", 500, 3, "https://img.example/c.jpg"));

        String html = service.pageFor(7, "https://shop.example/").orElseThrow();

        assertTrue(html.contains("<meta property=\"og:title\" content=\"Cetaphil - Charan Mart\">"));
        assertTrue(html.contains("Rs. 500.00 - facewash - in stock"));
        assertTrue(html.contains("<meta property=\"og:image\" content=\"https://img.example/c.jpg\">"));
        assertTrue(html.contains("<meta property=\"og:url\" content=\"https://shop.example/p/7\">"));
        assertTrue(html.contains("twitter:card\" content=\"summary_large_image\""));
        assertTrue(html.contains("content=\"0;url=https://shop.example/shop.html#product=7\""));
    }

    @Test
    void aSaleShowsTheOldPriceAndAnOutOfStockProductSaysSo() {
        Product p = product("Soap", 90, 0, null);
        p.setOnSale(true);
        p.setRegularPrice(120.0);
        when(productClient.getProductById(7)).thenReturn(p);

        String html = service.pageFor(7, "https://shop.example").orElseThrow();

        assertTrue(html.contains("Rs. 90.00 (on sale, was Rs. 120.00)"));
        assertTrue(html.contains("out of stock right now"));
    }

    @Test
    void noPictureTagForAMissingOrRelativeOrUnsafeImage() {
        for (String image : new String[]{null, "/uploads/a.png", "javascript:alert(1)", "https://x.example/a b.png"}) {
            when(productClient.getProductById(7)).thenReturn(product("Soap", 90, 1, image));
            String html = service.pageFor(7, "https://shop.example").orElseThrow();
            assertFalse(html.contains("og:image"), image);
            assertTrue(html.contains("twitter:card\" content=\"summary\""), image);
        }
    }

    @Test
    void everythingPutIntoThePageIsEscaped() {
        when(productClient.getProductById(7)).thenReturn(product("<script>alert(1)</script> \"x\" & 'y'", 10, 1, null));

        String html = service.pageFor(7, "https://shop.example").orElseThrow();

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &quot;x&quot; &amp; &#39;y&#39;"));
    }

    @Test
    void anUnknownProductOrADownCatalogGivesNoPage() {
        when(productClient.getProductById(7)).thenReturn(null);
        assertTrue(service.pageFor(7, "https://shop.example").isEmpty());
        when(productClient.getProductById(8)).thenThrow(new RuntimeException("down"));
        assertTrue(service.pageFor(8, "https://shop.example").isEmpty());
    }

    @Test
    void escapeHandlesEveryHtmlSpecialCharacter() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;ok", ProductPreviewService.esc("&<>\"'ok"));
    }
}
