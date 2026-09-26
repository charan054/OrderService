package com.example.orderservice.client;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.entity.OrderItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name="ProductService",url="http://localhost:8082")
public interface ProductClient {
    @GetMapping("/product/all")
    List<Product> findAll();
    @GetMapping("/product/byId")
    Product getProductById(@RequestParam int id);
    // Public on ProductService's side (see its SecurityConfig) - name is a partial, case-insensitive match;
    // category is exact. Used by OrderService.searchProducts() for the storefront's search box/category filter.
    @GetMapping("/product/search")
    ProductSearchResult search(@RequestParam(required = false) String name,
                                @RequestParam(required = false) String category,
                                @RequestParam int size);
    // ProductService requires X-Service-Key on every catalog-changing call (see its SecurityConfig).
    @PutMapping("/product/updateStock")
    public Product updateProductStock(@RequestHeader("X-Service-Key") String serviceKey, @RequestParam Integer id, @RequestParam Integer stock);

    // Public on ProductService's side - used by getCustomerProfile() to roll a review count into OrderService's
    // own cross-service customer summary.
    @GetMapping("/product/reviews/count")
    long getReviewCount(@RequestParam long phno);

    // Public on ProductService's side (see its SecurityConfig - matches "/product/*/rating-summary"). Used by
    // OrderService.getRatingSummaries() to surface star ratings on the storefront's product cards.
    @GetMapping("/product/{productId}/rating-summary")
    ProductRatingSummary getRatingSummary(@PathVariable long productId);

}
