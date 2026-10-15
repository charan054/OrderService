package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * The page behind a shared product link (/p/{id}). Chat apps and social sites read the page's title, description and
 * picture tags to draw a link card, but they never run JavaScript and never see the "#product=ID" part of a storefront
 * link, so the shared link points here instead: it carries those tags and then sends a real visitor on to the storefront
 * with the product open. Everything put into the page is escaped.
 */
@Service
public class ProductPreviewService {
    private final ProductClient productClient;

    public ProductPreviewService(ProductClient productClient) {
        this.productClient = productClient;
    }

    /** The preview page for a product, or empty when the product does not exist (or the catalog cannot be reached). */
    public Optional<String> pageFor(int productId, String baseUrl) {
        Product p;
        try {
            p = productClient.getProductById(productId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (p == null || p.getProductName() == null) {
            return Optional.empty();
        }
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String shop = base + "/shop.html#product=" + productId;
        String title = p.getProductName() + " - Charan Mart";
        String price = String.format(Locale.ROOT, "Rs. %.2f", p.getProductPrice());
        StringBuilder description = new StringBuilder(price);
        if (p.isOnSale() && p.getRegularPrice() != null && p.getRegularPrice() > p.getProductPrice()) {
            description.append(String.format(Locale.ROOT, " (on sale, was Rs. %.2f)", p.getRegularPrice()));
        }
        if (p.getProductCategory() != null && !p.getProductCategory().isBlank()) {
            description.append(" - ").append(p.getProductCategory());
        }
        description.append(p.getProductStock() > 0 ? " - in stock" : " - out of stock right now");

        StringBuilder html = new StringBuilder(1024);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(esc(title)).append("</title>")
                .append("<meta name=\"description\" content=\"").append(esc(description.toString())).append("\">")
                .append("<meta property=\"og:type\" content=\"product\">")
                .append("<meta property=\"og:site_name\" content=\"Charan Mart\">")
                .append("<meta property=\"og:title\" content=\"").append(esc(title)).append("\">")
                .append("<meta property=\"og:description\" content=\"").append(esc(description.toString())).append("\">")
                .append("<meta property=\"og:url\" content=\"").append(esc(base + "/p/" + productId)).append("\">");
        String image = p.getProductImageUrl();
        boolean hasImage = image != null && image.matches("(?i)https?://[^\\s<>\"'`]+");
        if (hasImage) {
            html.append("<meta property=\"og:image\" content=\"").append(esc(image)).append("\">");
        }
        html.append("<meta name=\"twitter:card\" content=\"").append(hasImage ? "summary_large_image" : "summary").append("\">")
                .append("<meta name=\"twitter:title\" content=\"").append(esc(title)).append("\">")
                .append("<meta name=\"twitter:description\" content=\"").append(esc(description.toString())).append("\">")
                // A visitor is sent straight on; the link below is for browsers that block both redirects.
                .append("<meta http-equiv=\"refresh\" content=\"0;url=").append(esc(shop)).append("\">")
                .append("</head><body><p><a href=\"").append(esc(shop)).append("\">Open ").append(esc(p.getProductName()))
                .append(" in Charan Mart</a></p></body></html>");
        return Optional.of(html.toString());
    }

    static String esc(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
