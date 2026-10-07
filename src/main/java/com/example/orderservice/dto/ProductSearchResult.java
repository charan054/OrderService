package com.example.orderservice.dto;

import java.util.List;

// Mirrors the "content" and "last" fields of the Page<Product> JSON that ProductService's GET /product/search
// returns - the rest of a Page's fields (totalElements, pageable, ...) are ignored. OrderService.searchProducts()
// walks the pages until "last" is true. A missing "last" (null) is treated as the final page, so a response
// without it can never loop forever.
public record ProductSearchResult(List<Product> content, Boolean last) {
    public ProductSearchResult(List<Product> content) {
        this(content, true);
    }

    public boolean isLastPage() {
        return last == null || last;
    }
}
