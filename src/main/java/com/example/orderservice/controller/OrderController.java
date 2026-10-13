package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.AdminOrderPage;
import com.example.orderservice.dto.AdminOrderRow;
import com.example.orderservice.dto.BulkTransitionResult;
import com.example.orderservice.dto.CancellationReport;
import com.example.orderservice.dto.ForgotPinRequest;
import com.example.orderservice.dto.OrderHistoryPage;
import com.example.orderservice.dto.FrequentlyBoughtTogether;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.ProductGalleryImage;
import com.example.orderservice.dto.ProductRatingSummary;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ResetPinRequest;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.dto.GuestOrderSummary;
import com.example.orderservice.dto.Invoice;
import com.example.orderservice.dto.InvoiceEmailResult;
import com.example.orderservice.service.InvoiceEmailService;
import com.example.orderservice.service.InvoicePdfService;
import com.example.orderservice.dto.LowStockAlertResult;
import com.example.orderservice.dto.LowStockItem;
import com.example.orderservice.dto.ModerationReview;
import com.example.orderservice.dto.SalesAnalytics;
import com.example.orderservice.dto.StorefrontReview;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.dto.ReorderReminderResult;
import com.example.orderservice.service.ReorderReminderService;
import com.example.orderservice.dto.SlowMover;
import com.example.orderservice.dto.ReorderSuggestion;
import com.example.orderservice.service.ReorderSuggestionService;
import com.example.orderservice.dto.MarkdownResult;
import com.example.orderservice.service.SlowMoverService;
import com.example.orderservice.dto.InventoryValue;
import com.example.orderservice.service.InventoryValueService;
import com.example.orderservice.entity.MissedSearch;
import com.example.orderservice.service.MissedSearchService;
import com.example.orderservice.entity.NotificationLog;
import com.example.orderservice.entity.TrackingEvent;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.dto.DigestResult;
import com.example.orderservice.dto.PendingPaymentSweepResult;
import com.example.orderservice.dto.StockAlertRunResult;
import com.example.orderservice.service.DigestService;
import com.example.orderservice.service.HealthService;
import com.example.orderservice.dto.ServiceHealth;
import com.example.orderservice.service.LowStockAlertService;
import com.example.orderservice.service.OrderService;
import com.example.orderservice.service.StockAlertService;
import jakarta.transaction.Transactional;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cart")
public class OrderController {
    @Autowired
    private com.example.orderservice.service.OrderTimelineService orderTimelineService;
    @Autowired
    private com.example.orderservice.service.ReturnApprovalService returnApprovalService;
    @Autowired
    private com.example.orderservice.service.ReturnPickupService returnPickupService;
    @Autowired
    private com.example.orderservice.service.ExchangeService exchangeService;
    @Autowired
    private com.example.orderservice.service.DeliveryFailureService deliveryFailureService;
    @Autowired
    private ReorderSuggestionService reorderSuggestionService;

    @Autowired
    private ReorderReminderService reorderReminderService;

    @Autowired
    private SlowMoverService slowMoverService;

    @Autowired
    private InventoryValueService inventoryValueService;

    @Autowired
    private MissedSearchService missedSearchService;

    @Autowired
    private OrderService orderService;
    @Autowired
    private StockAlertService stockAlertService;
    @Autowired
    private InvoiceEmailService invoiceEmailService;
    @Autowired
    private com.example.orderservice.service.RefundStatusService refundStatusService;
    @Autowired
    private InvoicePdfService invoicePdfService;
    @Autowired
    private DigestService digestService;
    @Autowired
    private LowStockAlertService lowStockAlertService;
    @Autowired
    private HealthService healthService;
    @Autowired
    private com.example.orderservice.service.RecommendationService recommendationService;
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
    // Same as /add above, but for the storefront: a signed-in customer may only place an order under their own
    // phone number (or the service key, for any). A PHONEPE order additionally needs a real successful debit.
    @PostMapping("/checkout")
    public Cart checkout(@RequestBody Cart cart,
                         @RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                         @RequestParam(required = false) Long payerPhno,
                         @RequestParam(required = false) String payerPin,
                         @RequestParam(required = false) String payerUpiId){
        CustomerAccess.requireSelfOrService(cart.getCustomerPhno());
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
    // payerPhno/payerPin are the same storefront-only fallback checkout has (see OrderController.checkout). The
    // caller must also be the order's owner (signed-in session) or the service key - see requireOrderAccess.
    @PostMapping("/{orderId}/cancel")
    public Cart cancelOrder(@PathVariable long orderId,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                            @RequestParam(required = false) Long payerPhno,
                            @RequestParam(required = false) String payerPin,
                            @RequestParam(required = false) String reason,
                            @RequestParam(required = false) String note,
                            @RequestParam(required = false) String refundTo){
        requireOrderAccess(orderId);
        if (!CustomerAccess.isService()) {
            orderService.assertCustomerMayCancel(orderId, reason);
        }
        return orderService.cancel(orderId, authorization, idempotencyKey, payerPhno, payerPin, reason, note, refundTo);
    }
    // The order's owner (signed-in session) or the service key: change delivery slot/instructions while still PLACED.
    // Omit a parameter to keep it, send it blank to clear it.
    @PutMapping("/{orderId}/delivery")
    public Cart rescheduleDelivery(@PathVariable long orderId,
                                   @RequestParam(required = false) String deliverySlot,
                                   @RequestParam(required = false) String deliveryNote){
        requireOrderAccess(orderId);
        return orderService.rescheduleDelivery(orderId, deliverySlot, deliveryNote);
    }
    // Admin-only (service key by default): why orders get cancelled.
    @GetMapping("/analytics/cancellations")
    public CancellationReport cancellationReport(){
        return orderService.getCancellationReport();
    }
    // Same buyer-token requirement as cancel above (waived for CASH, same reasoning), but only usable once an
    // order has reached DELIVERED - cancel and return are mutually exclusive by status, never overlapping windows.
    @PostMapping("/{orderId}/return")
    public Cart returnOrder(@PathVariable long orderId,
                            @RequestParam String reason,
                            @RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                            @RequestParam(required = false) Long payerPhno,
                            @RequestParam(required = false) String payerPin,
                            @RequestParam(required = false) String refundTo){
        requireOrderAccess(orderId);
        requireReturnApproved(orderId);
        // With pickups on, a customer's return only books the pickup; the refund comes when staff mark it received.
        if (returnPickupService.isEnabled() && !CustomerAccess.isService()) {
            return returnPickupService.schedule(orderId, reason, refundTo);
        }
        return orderService.returnOrder(orderId, authorization, idempotencyKey, reason, payerPhno, payerPin, refundTo);
    }
    // Moves a not-yet-shipped order to another of the customer's saved addresses; same access as cancel.
    @PutMapping("/{orderId}/address")
    public Cart changeAddress(@PathVariable long orderId, @RequestParam long addressId){
        requireOrderAccess(orderId);
        return orderService.changeOrderAddress(orderId, addressId);
    }
    // Adds units of a product to a not-yet-shipped cash order (see OrderService.addItem for the limits). Same access as
    // cancel: the order's signed-in owner, or the service key. No money moves, so no payment credentials are needed.
    @PostMapping("/{orderId}/items/add")
    public Cart addItem(@PathVariable long orderId, @RequestParam int productId, @RequestParam int quantity){
        requireOrderAccess(orderId);
        return orderService.addItem(orderId, productId, quantity);
    }
    // Per-item versions of cancel/return above: some units of one product in the order. Same access (the order's
    // signed-in owner, or the service key) and the same PhonePe credential rules for the partial refund.
    @PostMapping("/{orderId}/items/{productId}/cancel")
    public Cart cancelItem(@PathVariable long orderId, @PathVariable int productId,
                           @RequestParam int quantity,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                           @RequestParam(required = false) Long payerPhno,
                           @RequestParam(required = false) String payerPin,
                           @RequestParam(required = false) String refundTo){
        requireOrderAccess(orderId);
        if (!CustomerAccess.isService()) {
            orderService.assertCustomerMayCancel(orderId, "item");   // items carry no reason; only the lock applies in practice
        }
        return orderService.cancelItem(orderId, productId, quantity, authorization, idempotencyKey, payerPhno, payerPin, refundTo);
    }
    @PostMapping("/{orderId}/items/{productId}/return")
    public Cart returnItem(@PathVariable long orderId, @PathVariable int productId,
                           @RequestParam int quantity, @RequestParam String reason,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                           @RequestParam(required = false) Long payerPhno,
                           @RequestParam(required = false) String payerPin,
                           @RequestParam(required = false) String refundTo){
        requireOrderAccess(orderId);
        requireReturnApproved(orderId);
        // With pickups on, a customer's item return books a pickup instead (refunded when staff mark it received).
        if (returnPickupService.isEnabled() && !CustomerAccess.isService()) {
            return returnPickupService.scheduleItem(orderId, productId, quantity, reason, refundTo);
        }
        return orderService.returnItem(orderId, productId, quantity, reason, authorization, idempotencyKey, payerPhno, payerPin, refundTo);
    }
    // Operational actions (warehouse/ops moving an order along), not something the buyer's own token gates -
    // authenticated the same way as every other trusted-caller endpoint here, via X-Service-Key.
    @PostMapping("/{orderId}/ship")
    public Cart shipOrder(@PathVariable long orderId,
                          @RequestParam(required = false) String carrier,
                          @RequestParam(required = false) String trackingNumber){
        return orderService.ship(orderId, carrier, trackingNumber);
    }
    // Service key (default rule): fix the carrier/tracking number of an already-shipped order.
    @PutMapping("/{orderId}/shipment")
    public Cart updateShipment(@PathVariable long orderId,
                               @RequestParam(required = false) String carrier,
                               @RequestParam(required = false) String trackingNumber){
        return orderService.updateShipment(orderId, carrier, trackingNumber);
    }
    // Service-key only like ship/deliver: body is a JSON array of order ids, at most 100 per call.
    @PostMapping("/bulk/ship")
    public BulkTransitionResult bulkShip(@RequestBody List<Long> orderIds){
        return orderService.bulkTransition("ship", orderIds);
    }
    @PostMapping("/bulk/deliver")
    public BulkTransitionResult bulkDeliver(@RequestBody List<Long> orderIds){
        return orderService.bulkTransition("deliver", orderIds);
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
        requireOrderAccess(orderId);
        return orderService.checkPendingPayment(orderId);
    }
    // Public by order id - status + timestamps only, no customer details. Backs the logged-out order tracker.
    @GetMapping("/{orderId}/tracking")
    public List<TrackingEvent> getTracking(@PathVariable long orderId){
        return orderService.getTracking(orderId);
    }
    // Public: the logged-out "Track an order" box. Needs the matching phone number (mismatch -> 404) and returns
    // status/totals only - no address, items, or anything else /byphno would show a signed-in customer.
    @GetMapping("/{orderId}/summary")
    public GuestOrderSummary getGuestSummary(@PathVariable long orderId, @RequestParam long phno){
        return orderService.getGuestSummary(orderId, phno);
    }
    // Signed-in customer (own order only) or service; the service also checks phno matches the order's owner.
    @GetMapping("/{orderId}/invoice")
    public Invoice getInvoice(@PathVariable long orderId, @RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getInvoice(orderId, phno);
    }
    // The same invoice as a downloadable PDF (GST breakdown and credit notes included); same access rules as above.
    @GetMapping("/{orderId}/invoice.pdf")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable long orderId, @RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        Invoice invoice = orderService.getInvoice(orderId, phno);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(invoicePdfService.fileName(invoice)).build().toString())
                .body(invoicePdfService.render(invoice));
    }
    // Where the money of this order's cancellations/returns went (PhonePe, store credit, or no longer due). Signed-in
    // customer (own order only) or service, like the invoice.
    @GetMapping("/{orderId}/refunds")
    public com.example.orderservice.dto.RefundStatus getRefundStatus(@PathVariable long orderId, @RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        return refundStatusService.statusOf(orderId, phno);
    }
    // The order's whole story for the customer: status changes, order/return/pickup updates and refunds, oldest first.
    // Signed-in customer (own order only) or service, like the refund status.
    @GetMapping("/{orderId}/timeline")
    public List<com.example.orderservice.dto.TimelineEntry> getTimeline(@PathVariable long orderId, @RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        return orderTimelineService.timeline(orderId, phno);
    }
    // Emails the invoice to the customer's VERIFIED address (the caller can't choose one); rate-limited per order.
    @PostMapping("/{orderId}/invoice/email")
    public InvoiceEmailResult emailInvoice(@PathVariable long orderId, @RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        return invoiceEmailService.send(orderId, phno);
    }
    // Public by order id like tracking above - the audit trail of notifications dispatched for this order.
    @GetMapping("/{orderId}/notifications")
    public List<NotificationLog> getNotifications(@PathVariable long orderId){
        return orderService.getNotifications(orderId);
    }
    // Backs the storefront's "My notifications" panel - signed-in customer (own phone number only) or service key.
    @GetMapping("/notifications")
    public List<NotificationLog> getNotificationsForCustomer(@RequestParam long phno){
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getNotificationsForCustomer(phno);
    }
    @GetMapping("/display")
    public List<Product> findAll(){
        return orderService.getProducts();
    }
    // The flat delivery fee and the free-delivery threshold, for the storefront's cart panel (0 fee = no fee).
    @GetMapping("/deliveryfee")
    public java.util.Map<String, Double> deliveryFee() {
        return orderService.getDeliveryFeeTerms();
    }

    // Same public catalog-browsing trust level as /cart/display above - the storefront's search box/category
    // filter, proxied to ProductService's own public /product/search (see ProductClient.search).
    @GetMapping("/search")
    public List<Product> search(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) String category){
        List<Product> found = orderService.searchProducts(name, category);
        // Only a name search with no category filter says "the catalog lacks this"; a category pick can be empty for other reasons.
        if (found.isEmpty() && (category == null || category.isBlank())) {
            missedSearchService.record(name);
        }
        return found;
    }

    // Admin-only (service key by default): searches that found nothing, most frequent first.
    @GetMapping("/analytics/missed-searches")
    public List<MissedSearch> missedSearches(){
        return missedSearchService.top();
    }

    // Admin-only: drop one term from the report once it has been dealt with (it comes back if shoppers search it again).
    @DeleteMapping("/analytics/missed-searches")
    public ResponseEntity<Void> dismissMissedSearch(@RequestParam String term){
        return missedSearchService.dismiss(term) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
    // Same public catalog-browsing trust level as /cart/display above - star ratings for the storefront's
    // product cards, proxied to ProductService's own public per-product rating-summary endpoint.
    @GetMapping("/ratings")
    public List<ProductRatingSummary> getRatings(@RequestParam List<Integer> productIds){
        return orderService.getRatingSummaries(productIds);
    }
    // "Picked for you": a signed-in customer (own phone number only) or the service key. Built from this customer's
    // own purchases, so unlike frequently-bought-together it is personal data.
    @GetMapping("/recommendations")
    public List<com.example.orderservice.dto.Recommendation> recommendations(@RequestParam long phno,
                                                                             @RequestParam(required = false) Integer limit) {
        CustomerAccess.requireSelfOrService(phno);
        return recommendationService.forCustomer(phno, limit);
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
    // Signed-in customer, posting only under their own phone number (or the service key).
    @PostMapping("/reviews")
    public StorefrontReview addReview(@RequestParam long productId, @RequestBody ReviewSubmission review) {
        CustomerAccess.requireSelfOrService(review.reviewerPhno());
        return orderService.addProductReview(productId, review.reviewerName(), review.reviewerPhno(), review.rating(), review.comment(),
                review.photoUrl());
    }
    // Signed-in customer (any - a report names no one's phone) or service: report a review for moderation.
    @PostMapping("/reviews/flag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void flagReview(@RequestParam long productId, @RequestParam long reviewId,
                           @RequestParam(required = false) String reason) {
        orderService.flagReview(productId, reviewId, reason);
    }
    // Admin moderation (service-only by default, like /analytics): the flagged-review queue and hide/unhide.
    @GetMapping("/reviews/flagged")
    public List<ModerationReview> getFlaggedReviews(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return orderService.getFlaggedReviews(page, size);
    }
    @PutMapping("/reviews/{reviewId}/hide")
    public ModerationReview hideReview(@PathVariable long reviewId, @RequestParam long productId) {
        return orderService.hideReview(productId, reviewId);
    }
    @PutMapping("/reviews/{reviewId}/unhide")
    public ModerationReview unhideReview(@PathVariable long reviewId, @RequestParam long productId) {
        return orderService.unhideReview(productId, reviewId);
    }
    // Public, same catalog-browsing trust level as /cart/display - straight proxy to ProductService's own
    // public gallery listing (see OrderService.getGalleryImages for why this exists at all).
    @GetMapping("/gallery")
    public List<ProductGalleryImage> getGalleryImages(@RequestParam long productId) {
        return orderService.getGalleryImages(productId);
    }
    @GetMapping("/byphno")
    public List<Cart> findByPhno(long phno){
        CustomerAccess.requireSelfOrService(phno);
        return orderService.ordersOfPhno(phno);
    }
    // Signed-in customer (own phone number only) or service: one page of that customer's orders, newest first,
    // filtered by status and/or placed-date range (yyyy-MM-dd).
    @GetMapping("/history")
    public OrderHistoryPage history(@RequestParam long phno,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                    @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size){
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getOrderHistory(phno, status, from, to, page, size);
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
    // Admin-only like /analytics: revenue and order count per day or week (bucket=day|week) between from and to
    // (yyyy-MM-dd, inclusive; default the last 30 days), in the given IANA time zone (default UTC).
    @GetMapping("/analytics/timeseries")
    public com.example.orderservice.dto.RevenueTimeseries getRevenueTimeseries(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String zone){
        return orderService.getRevenueTimeseries(from, to, bucket, zone);
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
    // Paged + sortable version of /orders/search for the admin table (see OrderService.searchOrdersPage).
    @GetMapping("/orders/page")
    public AdminOrderPage searchOrdersPage(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String paymentMethod,
                                           @RequestParam(required = false) Long phno,
                                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(required = false) String dir,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size){
        return orderService.searchOrdersPage(status, paymentMethod, phno, from, to, sort, dir, page, size);
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
    // Admin-only (service key by default): the daily digest as it would be emailed right now, without sending it.
    @GetMapping("/digest/preview")
    public DigestResult previewDigest(){
        return digestService.preview();
    }
    // Admin-only: is every service of the stack answering, and is the database reachable.
    @GetMapping("/health")
    public List<ServiceHealth> health(){
        return healthService.check();
    }
    // Admin-only: run the low-stock alert now (emails only products that newly dropped below their threshold).
    @PostMapping("/lowstock/alert")
    public LowStockAlertResult runLowStockAlert(){
        return lowStockAlertService.run();
    }
    // Admin-only: email the digest now (needs ADMIN_EMAIL / digest.to) instead of waiting for the daily run.
    @PostMapping("/digest/send")
    public DigestResult sendDigest(){
        return digestService.send();
    }
    // Admin-only (service key by default): resolve every pending UPI order now (cancel expired/declined ones and
    // put their stock back, finalize approved ones) instead of waiting for the next scheduled sweep.
    @PostMapping("/pending/sweep")
    public PendingPaymentSweepResult sweepPendingPayments(){
        return orderService.sweepPendingPayments();
    }
    // Admin-only (service key by default): run the restock / price-drop email job now instead of waiting for the
    // next scheduled run. Safe to call any time - each restock and each new lower price is emailed only once.
    @PostMapping("/alerts/run")
    public StockAlertRunResult runStockAlerts(){
        return stockAlertService.run();
    }
    // Admin-only (service key by default): run the "time to reorder?" email job now. Each repeat purchase is nudged once.
    @PostMapping("/reorder-reminders/run")
    public ReorderReminderResult runReorderReminders(){
        return reorderReminderService.run();
    }
    // Admin-only (service key by default): products with stock that have not sold in the last `days` days (default 30).
    @GetMapping("/analytics/slow-movers")
    public List<SlowMover> slowMovers(@RequestParam(required = false) Integer days){
        return slowMoverService.report(days);
    }
    // Admin-only (service key by default): products that would run out within `cover` days at the pace of the last
    // `days` days of sales, with how many units to add (defaults 30 and 30).
    @GetMapping("/analytics/reorder-suggestions")
    public List<ReorderSuggestion> reorderSuggestions(@RequestParam(required = false) Integer days,
                                                      @RequestParam(required = false) Integer cover){
        return reorderSuggestionService.report(days, cover);
    }
    // Admin-only (service key by default): lower one product's price by `percent` (1-70) of its current price. Lands
    // in the price history and triggers the wishlist price-drop alerts.
    @PostMapping("/analytics/slow-movers/markdown")
    public MarkdownResult markdownSlowMover(@RequestParam int productId, @RequestParam int percent){
        return slowMoverService.markdown(productId, percent);
    }
    // Admin-only (service key by default): what the current stock is worth at selling price, per category.
    @GetMapping("/analytics/inventory-value")
    public InventoryValue inventoryValue(){
        return inventoryValueService.report();
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

    // A customer may only act on their own order; anyone else's order id answers 404 exactly like a nonexistent
    // one, so ids can't be probed. A service-key caller may act on any order.
    // With returns.require-approval on, a customer (not staff) may only return an order that staff approved.
    private void requireReturnApproved(long orderId) {
        if (!CustomerAccess.isService()) {
            returnApprovalService.assertCustomerMayReturn(orderId);
        }
    }

    // The customer asks to return a delivered order (only when returns.require-approval is on).
    @PostMapping("/{orderId}/return/request")
    public Cart requestReturn(@PathVariable long orderId, @RequestParam String reason) {
        requireOrderAccess(orderId);
        return returnApprovalService.request(orderId, reason);
    }

    // Staff decisions (service key; MANAGER and up for named admins) and the waiting list.
    @PostMapping("/{orderId}/return/approve")
    public Cart approveReturn(@PathVariable long orderId) {
        return returnApprovalService.approve(orderId);
    }

    @PostMapping("/{orderId}/return/reject")
    public Cart rejectReturn(@PathVariable long orderId, @RequestParam(required = false) String note) {
        return returnApprovalService.reject(orderId, note);
    }

    // Staff: the pickup steps of a booked return, and the list of returns still on their way back.
    @PostMapping("/{orderId}/return/pickedup")
    public Cart returnPickedUp(@PathVariable long orderId) {
        return returnPickupService.markPickedUp(orderId);
    }

    @PostMapping("/{orderId}/return/received")
    public Cart returnReceived(@PathVariable long orderId) {
        return returnPickupService.markReceived(orderId);
    }

    @GetMapping("/returns/item-pickups")
    public List<com.example.orderservice.entity.ItemReturnPickup> itemReturnPickups() {
        return returnPickupService.openItems();
    }

    @PostMapping("/returns/item-pickups/{id}/pickedup")
    public com.example.orderservice.entity.ItemReturnPickup itemPickedUp(@PathVariable long id) {
        return returnPickupService.markItemPickedUp(id);
    }

    @PostMapping("/returns/item-pickups/{id}/received")
    public com.example.orderservice.entity.ItemReturnPickup itemReceived(@PathVariable long id) {
        return returnPickupService.markItemReceived(id);
    }

    // Exchange a delivered item for another product at the same price or lower (no money moves). The customer asks and
    // may withdraw while it waits; staff approve/reject and mark it received (service key).
    @PostMapping("/{orderId}/exchange")
    public com.example.orderservice.entity.ExchangeRequest requestExchange(@PathVariable long orderId, @RequestParam int productId,
                                                                           @RequestParam int quantity, @RequestParam int replacementProductId,
                                                                           @RequestParam String reason) {
        requireOrderAccess(orderId);
        return exchangeService.request(orderId, productId, quantity, replacementProductId, reason);
    }

    @GetMapping("/{orderId}/exchanges")
    public List<com.example.orderservice.entity.ExchangeRequest> orderExchanges(@PathVariable long orderId) {
        requireOrderAccess(orderId);
        return exchangeService.forOrder(orderId);
    }

    @PostMapping("/exchanges/{id}/withdraw")
    public com.example.orderservice.entity.ExchangeRequest withdrawExchange(@PathVariable long id) {
        requireOrderAccess(exchangeService.find(id).getOrderId());
        return exchangeService.cancel(id);
    }

    @GetMapping("/exchanges/open")
    public List<com.example.orderservice.entity.ExchangeRequest> openExchanges() {
        return exchangeService.open();
    }

    @PostMapping("/exchanges/{id}/approve")
    public com.example.orderservice.entity.ExchangeRequest approveExchange(@PathVariable long id) {
        return exchangeService.approve(id);
    }

    @PostMapping("/exchanges/{id}/reject")
    public com.example.orderservice.entity.ExchangeRequest rejectExchange(@PathVariable long id, @RequestParam(required = false) String note) {
        return exchangeService.reject(id, note);
    }

    @PostMapping("/exchanges/{id}/received")
    public com.example.orderservice.entity.ExchangeRequest exchangeReceived(@PathVariable long id) {
        return exchangeService.markReceived(id);
    }

    // Staff: a delivery attempt failed (the order stays shipped); try again, or send it back to stock (cancel + refund).
    @PostMapping("/{orderId}/delivery-failed")
    public com.example.orderservice.service.DeliveryFailureService.Result deliveryFailed(@PathVariable long orderId, @RequestParam String reason,
                                                                                         @RequestParam(required = false) String note) {
        return deliveryFailureService.record(orderId, reason, note);
    }

    @PostMapping("/{orderId}/delivery-failed/return-to-stock")
    public Cart returnUndelivered(@PathVariable long orderId, @RequestParam(required = false) String note) {
        return deliveryFailureService.returnToStock(orderId, note);
    }

    @GetMapping("/{orderId}/delivery-attempts")
    public List<com.example.orderservice.entity.DeliveryAttempt> deliveryAttempts(@PathVariable long orderId) {
        requireOrderAccess(orderId);
        return deliveryFailureService.forOrder(orderId);
    }

    @GetMapping("/returns/pickups")
    public List<Cart> returnPickups() {
        return returnPickupService.open();
    }

    @GetMapping("/returns/pending")
    public List<Cart> pendingReturns() {
        return returnApprovalService.pending();
    }

    // Whether the storefront should offer "Request return" and wait for approval first.
    // How long a customer may cancel freely / at all (public; shown in the storefront's cancel prompt).
    @GetMapping("/cancelpolicy")
    public java.util.Map<String, Integer> cancelPolicy() {
        return orderService.getCancelPolicy();
    }

    @GetMapping("/returnpolicy")
    public java.util.Map<String, Boolean> returnPolicy() {
        return java.util.Map.of("approvalRequired", returnApprovalService.isRequired(), "pickup", returnPickupService.isEnabled());
    }

    private void requireOrderAccess(long orderId) {
        if (!CustomerAccess.canAccess(orderService.ownerPhnoOf(orderId))) {
            throw new OrderNotFoundException("Order not found");
        }
    }
}
