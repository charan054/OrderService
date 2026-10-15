package com.example.orderservice.controller;

import com.example.orderservice.service.ProductPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

// Public: the page a shared product link opens (title, description and picture tags for link cards, then on to the
// storefront). Carries nothing but what the public catalog already shows.
@RestController
public class ProductPreviewController {
    private final ProductPreviewService service;
    private final String configuredBaseUrl;

    public ProductPreviewController(ProductPreviewService service, @Value("${email.public-base-url:}") String configuredBaseUrl) {
        this.service = service;
        this.configuredBaseUrl = configuredBaseUrl;
    }

    @GetMapping(value = "/p/{productId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@PathVariable int productId, HttpServletRequest request) {
        // The shop's configured public address when there is one (a link card needs an absolute URL that works from
        // outside); otherwise whatever address this request came in on.
        String base = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? ServletUriComponentsBuilder.fromRequestUri(request).replacePath(null).replaceQuery(null).build().toUriString()
                : configuredBaseUrl;
        return service.pageFor(productId, base)
                .map(html -> ResponseEntity.ok().body(html))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Charan Mart</title></head>"
                                + "<body><p>That product is no longer available. <a href=\"/shop.html\">Browse Charan Mart</a></p></body></html>"));
    }
}
