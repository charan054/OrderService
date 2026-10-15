package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.entity.PriceMatchRequest;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.PriceMatchRequestRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * "Found it cheaper elsewhere": customers send a competitor's price and link for a product, the store approves or
 * declines with a note. Approving changes no price by itself - it is a record and a message back to the customer.
 */
@Service
public class PriceMatchService {
    static final int MAX_OPEN_PER_CUSTOMER = 5;
    static final int MAX_URL_LENGTH = 500;
    static final int MAX_NOTE_LENGTH = 300;
    static final int DEFAULT_SALE_DAYS = 7;
    static final int MAX_SALE_DAYS = 90;

    private final PriceMatchRequestRepository requests;
    private final ProductClient productClient;
    private final Clock clock;
    private final CustomerNotifier notifier;
    private final String serviceApiKey;

    public PriceMatchService(PriceMatchRequestRepository requests, ProductClient productClient, Clock clock,
                             CustomerNotifier notifier,
                             @org.springframework.beans.factory.annotation.Value("${internal.service.api-key}") String serviceApiKey) {
        this.notifier = notifier;
        this.serviceApiKey = serviceApiKey;
        this.requests = requests;
        this.productClient = productClient;
        this.clock = clock;
    }

    public PriceMatchRequest request(long phno, int productId, double competitorPrice, String competitorUrl) {
        validatePhno(phno);
        if (!Double.isFinite(competitorPrice) || competitorPrice <= 0) {
            throw new ProductException("Enter the price you found, more than 0");
        }
        String url = competitorUrl == null ? "" : competitorUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH || !(lower.startsWith("http://") || lower.startsWith("https://"))) {
            throw new ProductException("Add the web link (http or https, up to " + MAX_URL_LENGTH + " characters) where you saw the price");
        }
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product not found");
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        if (competitorPrice >= product.getProductPrice()) {
            throw new ProductException("Our price (Rs. " + product.getProductPrice() + ") is already the same or lower");
        }
        if (requests.existsByCustomerPhnoAndProductIdAndStatus(phno, productId, PriceMatchRequest.Status.OPEN)) {
            throw new ProductException("You already have an open price-match request for this product");
        }
        if (requests.countByCustomerPhnoAndStatus(phno, PriceMatchRequest.Status.OPEN) >= MAX_OPEN_PER_CUSTOMER) {
            throw new ProductException("You already have " + MAX_OPEN_PER_CUSTOMER
                    + " open price-match requests - please wait for answers before sending more");
        }
        PriceMatchRequest r = new PriceMatchRequest();
        r.setProductId(productId);
        r.setCustomerPhno(phno);
        r.setCompetitorPrice(competitorPrice);
        r.setOurPrice(product.getProductPrice());
        r.setCompetitorUrl(url);
        r.setStatus(PriceMatchRequest.Status.OPEN);
        r.setCreatedAt(Instant.now(clock));
        return requests.save(r);
    }

    public List<PriceMatchRequest> mine(long phno) {
        validatePhno(phno);
        return requests.findByCustomerPhnoOrderByIdDesc(phno);
    }

    public List<PriceMatchRequest> open() {
        return requests.findByStatusOrderByIdAsc(PriceMatchRequest.Status.OPEN);
    }

    public PriceMatchRequest resolve(long id, boolean approved, String note) {
        return resolve(id, approved, note, null, null);
    }

    /**
     * Decides a request. When approving, an optional salePrice also starts a timed sale on the product for saleDays
     * (default 7) through ProductService. The sale is started first: if ProductService refuses it (price not below the
     * regular price, product gone, service down) nothing is changed and the request stays open.
     */
    public PriceMatchRequest resolve(long id, boolean approved, String note, Double salePrice, Integer saleDays) {
        String text = note == null ? "" : note.trim();
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new ProductException("Note can be at most " + MAX_NOTE_LENGTH + " characters");
        }
        PriceMatchRequest r = requests.findById(id).orElseThrow(() -> new ProductException("Request not found"));
        if (r.getStatus() != PriceMatchRequest.Status.OPEN) {
            throw new ProductException("Request already " + r.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (salePrice != null) {
            if (!approved) {
                throw new ProductException("A sale can only be started when approving");
            }
            int days = saleDays == null ? DEFAULT_SALE_DAYS : saleDays;
            if (!Double.isFinite(salePrice) || salePrice <= 0) {
                throw new ProductException("Sale price must be more than 0");
            }
            if (days < 1 || days > MAX_SALE_DAYS) {
                throw new ProductException("A sale can run for 1 to " + MAX_SALE_DAYS + " days");
            }
            Instant endsAt = Instant.now(clock).plus(java.time.Duration.ofDays(days));
            try {
                productClient.setProductSale(serviceApiKey, r.getProductId(), salePrice, endsAt);
            } catch (FeignException e) {
                throw new ProductException("Could not start the sale: " + saleFailure(e));
            }
            r.setSalePrice(salePrice);
            r.setSaleEndsAt(endsAt);
        }
        r.setStatus(approved ? PriceMatchRequest.Status.APPROVED : PriceMatchRequest.Status.DECLINED);
        r.setAdminNote(text.isEmpty() ? null : text);
        r.setResolvedAt(Instant.now(clock));
        PriceMatchRequest saved = requests.save(r);
        notifier.notifyPriceMatchDecision(saved.getCustomerPhno(), productNameOf(saved.getProductId()),
                saved.getCompetitorPrice(), approved, saved.getAdminNote(), saved.getSalePrice(), saved.getSaleEndsAt());
        return saved;
    }

    private static String saleFailure(FeignException e) {
        String body = e.contentUTF8();
        return body == null || body.isBlank() ? "ProductService answered " + e.status() : body;
    }

    // The name for the email; a product that has since gone (or a catalog that is down) must not block the decision.
    private String productNameOf(int productId) {
        try {
            Product p = productClient.getProductById(productId);
            if (p != null && p.getProductName() != null && !p.getProductName().isBlank()) {
                return p.getProductName();
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return "product #" + productId;
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
