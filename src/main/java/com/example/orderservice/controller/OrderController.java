package com.example.orderservice.controller;

import com.example.orderservice.dto.AdminOrderRow;
import com.example.orderservice.dto.ForgotPinRequest;
import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ResetPinRequest;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.SalesAnalytics;
import com.example.orderservice.dto.StorefrontReview;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.service.OrderService;
import jakarta.transaction.Transactional;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cart")
public class OrderController {
    @Autowired
    private OrderService orderService;
    // Authorization is the buyer's OWN PhonepayService session token ("Bearer <token>") - that is who gets
    // charged. It's optional here (unlike before) only so the storefront checkout can instead send payerPhno/
    // payerPin for a PHONEPE order with no token yet; OrderService exchanges those for a token itself via
    // PhonepayService's own /phonepe/login (see OrderService.resolveBuyerToken) - the PIN is never stored, only
    // used in-memory for that one call. A CASH order needs neither. Idempotency-Key is optional: send the same
    // value on a retry of the same checkout attempt (e.g. after a lost response) to avoid paying twice; a
    // different value, or none, is always a brand new payment.
    @PostMapping("/add")
    public Cart addOrder(@RequestBody Cart cart,
                         @RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                         @RequestParam(required = false) Long payerPhno,
                         @RequestParam(required = false) String payerPin,
                         @RequestParam(required = false) String payerUpiId){
        return orderService.order(cart, authorization, idempotencyKey, payerPhno, payerPin, payerUpiId);
    }
    // Same as /add above, but without the X-Service-Key requirement - this is the one write endpoint a genuine
    // customer-facing storefront can call directly, since it has no way to know that internal secret (see
    // SecurityConfig). The only gate against abuse is the same one /add already has for a PHONEPE order: a real
    // successful debit through PhonepayService. A CASH order has no such gate, same trust level /cart/byphno
    // already extends to "anyone who knows a phone number" elsewhere in this system.
    @PostMapping("/checkout")
    public Cart checkout(@RequestBody Cart cart,
                         @RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                         @RequestParam(required = false) Long payerPhno,
                         @RequestParam(required = false) String payerPin,
                         @RequestParam(required = false) String payerUpiId){
        return orderService.order(cart, authorization, idempotencyKey, payerPhno, payerPin, payerUpiId);
    }
    // Pure proxy to PhonepayService's own forgot-PIN flow (which itself proxies to Bankapplication) - shop.html
    // has no way to call either origin directly. See OrderService.forgotPinRequest/resetPin.
    @PostMapping("/forgotpin/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPinRequest(@RequestBody ForgotPinRequest request) {
        orderService.forgotPinRequest(request.phno());
    }

    @PostMapping("/forgotpin/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPinReset(@RequestBody ResetPinRequest request) {
        orderService.resetPin(request.phno(), request.otp(), request.newPin());
    }
    // Authorization must be the buyer's OWN PhonepayService session token for a PHONEPE order - PhonepayService
    // only refunds a payment back to the person who made it, so this can never cancel (and refund) someone
    // else's order. Not required for a CASH order, which was never charged and so has nothing to refund.
    // payerPhno/payerPin are the same storefront-only fallback checkout has (see OrderController.checkout) - the
    // customer-facing cancel button has no stored session token, only a phone+PIN entered fresh for this call.
    @PostMapping("/{orderId}/cancel")
    public Cart cancelOrder(@PathVariable long orderId,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                            @RequestParam(required = false) Long payerPhno,
                            @RequestParam(required = false) String payerPin){
        return orderService.cancel(orderId, authorization, idempotencyKey, payerPhno, payerPin);
    }
    // Same buyer-token requirement as cancel above (waived for CASH, same reasoning), but only usable once an
    // order has reached DELIVERED - cancel and return are mutually exclusive by status, never overlapping windows.
    @PostMapping("/{orderId}/return")
    public Cart returnOrder(@PathVariable long orderId,
                            @RequestParam String reason,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                            @RequestParam(required = false) Long payerPhno,
                            @RequestParam(required = false) String payerPin){
        return orderService.returnOrder(orderId, authorization, idempotencyKey, reason, payerPhno, payerPin);
    }
    // Operational actions (warehouse/ops moving an order along), not something the buyer's own token gates -
    // authenticated the same way as every other trusted-caller endpoint here, via X-Service-Key.
    @PostMapping("/{orderId}/ship")
    public Cart shipOrder(@PathVariable long orderId){
        return orderService.ship(orderId);
    }
    @PostMapping("/{orderId}/deliver")
    public Cart deliverOrder(@PathVariable long orderId){
        return orderService.deliver(orderId);
    }
    // Ops action: records that a cash-on-delivery order's payment was actually collected. Same X-Service-Key
    // trust level as ship/deliver above - see OrderService.markPaid() for why this isn't automatic.
    @PostMapping("/{orderId}/markpaid")
    public Cart markPaid(@PathVariable long orderId){
        return orderService.markPaid(orderId);
    }
    // Polled by the storefront while a UPI-collect order sits PENDING_PAYMENT and the buyer goes to approve it in
    // PhonepayService - each call lazily resolves the order against PhonepayService's own collect-request status
    // (or OrderService's own deadline) before returning it, same "checked the moment anything next touches it"
    // reasoning as the rest of this system's lazy expiry. A no-op for any order not currently PENDING_PAYMENT.
    @GetMapping("/{orderId}/paymentstatus")
    public Cart getPaymentStatus(@PathVariable long orderId){
        return orderService.checkPendingPayment(orderId);
    }
    // Public, same self-service trust level as GET /cart/byphno - the timeline is just a history of the same
    // status field that /cart/byphno already exposes, one row per transition instead of a single current value.
    @GetMapping("/{orderId}/tracking")
    public List<TrackingEvent> getTracking(@PathVariable long orderId){
        return orderService.getTracking(orderId);
    }
    // Public like /byphno, but the phone number must match the order's owner (see OrderService.getInvoice).
    @GetMapping("/{orderId}/invoice")
    public Invoice getInvoice(@PathVariable long orderId, @RequestParam long phno){
        return orderService.getInvoice(orderId, phno);
    }
    // Same public trust level as tracking above - the audit trail of customer notifications OrderKafkaConsumer
    // has dispatched for this order so far.
    @GetMapping("/{orderId}/notifications")
    public List<NotificationLog> getNotifications(@PathVariable long orderId){
        return orderService.getNotifications(orderId);
    }
    // Backs the storefront's "My notifications" panel - same public, self-service trust level as /cart/byphno.
    @GetMapping("/notifications")
    public List<NotificationLog> getNotificationsForCustomer(@RequestParam long phno){
        return orderService.getNotificationsForCustomer(phno);
    }
    @GetMapping("/display")
    public List<Product> findAll(){
        return orderService.getProducts();
    }
    // Same public catalog-browsing trust level as /cart/display above - the storefront's search box/category
    // filter, proxied to ProductService's own public /product/search (see ProductClient.search).
    @GetMapping("/search")
    public List<Product> search(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) String category){
        return orderService.searchProducts(name, category);
    }
    // Same public catalog-browsing trust level as /cart/display above - star ratings for the storefront's
    // product cards, proxied to ProductService's own public per-product rating-summary endpoint.
    @GetMapping("/ratings")
    public List<ProductRatingSummary> getRatings(@RequestParam List<Integer> productIds){
        return orderService.getRatingSummaries(productIds);
    }
    // Public, same catalog-browsing trust level as /cart/display - a ranked list, not any one customer's data.
    @GetMapping("/frequentlyboughttogether")
    public List<FrequentlyBoughtTogether> getFrequentlyBoughtTogether(@RequestParam int productId,
                                                                       @RequestParam(required = false) Integer limit) {
        return orderService.getFrequentlyBoughtTogether(productId, limit);
    }
    // Public, same catalog-browsing trust level as /cart/display - straight proxy to ProductService's own
    // public review listing (see OrderService.getProductReviews for why this exists at all).
    @GetMapping("/reviews")
    public List<StorefrontReview> getReviews(@RequestParam long productId,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return orderService.getProductReviews(productId, page, size);
    }
    // Public, same self-service trust level as posting to your own wishlist/addresses - a customer reviewing a
    // product they browsed needs no X-Service-Key, mirrors ProductService's own review posting being public too.
    @PostMapping("/reviews")
    public StorefrontReview addReview(@RequestParam long productId, @RequestBody ReviewSubmission review) {
        return orderService.addProductReview(productId, review.reviewerName(), review.reviewerPhno(), review.rating(), review.comment());
    }
    // Public, same catalog-browsing trust level as /cart/display - straight proxy to ProductService's own
    // public gallery listing (see OrderService.getGalleryImages for why this exists at all).
    @GetMapping("/gallery")
    public List<ProductGalleryImage> getGalleryImages(@RequestParam long productId) {
        return orderService.getGalleryImages(productId);
    }
    @GetMapping("/byphno")
    public List<Cart> findByPhno(long phno){
        return orderService.ordersOfPhno(phno);
    }
    @GetMapping("/all")
    public List<Cart> getAll(){
        return orderService.findAll();
    }
    // Admin-only sales rollup, same X-Service-Key trust level as /cart/all above (which this is built from) -
    // not something a customer's own phone number should be able to pull.
    @GetMapping("/analytics")
    public SalesAnalytics getSalesAnalytics(){
        return orderService.getSalesAnalytics();
    }
    // Admin orders table + CSV download (X-Service-Key gated by default). Dates are yyyy-MM-dd, inclusive.
    @GetMapping("/orders/search")
    public List<AdminOrderRow> searchOrders(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String paymentMethod,
                                            @RequestParam(required = false) Long phno,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to){
        return orderService.searchOrders(status, paymentMethod, phno, from, to);
    }
    @GetMapping(value = "/orders/export", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> exportOrders(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String paymentMethod,
                                            @RequestParam(required = false) Long phno,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to){
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"orders.csv\"")
                .body(orderService.exportOrdersCsv(status, paymentMethod, phno, from, to));
    }
    // Admin-only restock report (X-Service-Key gated by default, like /analytics) - see OrderService.getLowStockReport().
    @GetMapping("/lowstock")
    public List<LowStockItem> getLowStock(){
        return orderService.getLowStockReport();
    }
    @Transactional
    @DeleteMapping("/deleteproduct")
    public List<Cart> deleteProduct(@RequestParam long phno,@RequestParam long productId){
        return orderService.deleteProduct(phno,productId);
    }
}
