package com.example.orderservice.dto;

import java.util.List;

// Mirrors just the "content" field of the Page<Product> JSON that ProductService's GET /product/search returns -
// the rest of a Page's fields (totalElements, totalPages, pageable, ...) are ignored rather than modeled, since
// OrderService.searchProducts() only ever needs the matching products themselves, not pagination metadata.
public record ProductSearchResult(List<Product> content) {
}
