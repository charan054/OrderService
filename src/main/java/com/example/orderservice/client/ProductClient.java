package com.example.orderservice.client;

import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ModerationReview;
import com.example.orderservice.dto.ModerationReviewsResult;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ProductReviewsResult;
import com.example.orderservice.dto.ProductSearchResult;
import com.example.orderservice.dto.RecentStockChange;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.entity.OrderItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
                                @RequestParam int page,
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

    // ProductService's public listing omits reviewerPhno (it doubles as the ownership check for update/delete),
    // so the Verified-purchase badge reads from the X-Service-Key-only internal listing instead. Used by
    // OrderService's own /cart/reviews proxy, since shop.html only ever calls its own origin.
    @GetMapping("/product/stock-movements/recent")
    List<RecentStockChange> getRecentStockChanges(@RequestHeader("X-Service-Key") String serviceKey, @RequestParam int hours);
    @GetMapping("/product/internal/{productId}/reviews")
    ProductReviewsResult getReviews(@RequestHeader("X-Service-Key") String serviceKey, @PathVariable long productId,
                                    @RequestParam int page, @RequestParam int size);

    // Every photo link a review (hidden ones too) still uses - X-Service-Key only. ReviewPhotoTidyService deletes
    // the uploaded files that nothing in this list names.
    @GetMapping("/product/reviews/photos")
    List<String> getReviewPhotoUrls(@RequestHeader("X-Service-Key") String serviceKey);

    // Account deletion: takes the customer's name, phone number and photo off the reviews they wrote (the reviews stay).
    // X-Service-Key only on ProductService's side. Returns how many reviews changed.
    @PutMapping("/product/reviews/anonymise")
    int anonymiseReviews(@RequestHeader("X-Service-Key") String serviceKey, @RequestParam long phno);

    // Public on ProductService's side (matches "/product/*/reviews", POST open).

    @PostMapping("/product/{productId}/reviews")
    ProductReview addReview(@PathVariable long productId, @RequestBody ReviewSubmission review);

    // Public on ProductService's side (see its SecurityConfig - matches "/product/*/images"). Used by
    // OrderService's own /cart/gallery proxy, since shop.html only ever calls its own origin.
    @GetMapping("/product/{productId}/images")
    List<ProductGalleryImage> getGalleryImages(@PathVariable long productId);

    // Review moderation (ProductService's own endpoints): flagging is public there, the queue and hide/unhide need
    // X-Service-Key. Used by OrderService's /cart/reviews/flag(ged)|hide|unhide, since cart.html/shop.html only
    // ever call their own origin.
    @PostMapping("/product/{productId}/reviews/{reviewId}/flag")
    void flagReview(@PathVariable long productId, @PathVariable long reviewId, @RequestParam(required = false) String reason);

    @GetMapping("/product/reviews/flagged")
    ModerationReviewsResult getFlaggedReviews(@RequestHeader("X-Service-Key") String serviceKey,
                                              @RequestParam int page, @RequestParam int size);

    @PutMapping("/product/{productId}/reviews/{reviewId}/hide")
    ModerationReview hideReview(@RequestHeader("X-Service-Key") String serviceKey,
                                @PathVariable long productId, @PathVariable long reviewId);

    @PutMapping("/product/{productId}/reviews/{reviewId}/unhide")
    ModerationReview unhideReview(@RequestHeader("X-Service-Key") String serviceKey,
                                  @PathVariable long productId, @PathVariable long reviewId);
}
