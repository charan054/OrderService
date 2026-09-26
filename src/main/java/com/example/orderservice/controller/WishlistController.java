package com.example.orderservice.controller;

import com.example.orderservice.dto.WishlistPriceAlert;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /wishlist/byphno and /wishlist/pricedrops are public (see SecurityConfig), the same self-service trust
// level as GET /cart/byphno - looking up your own list/alerts by your own phone number. /add and /remove are
// mutations and stay under the default "anyRequest().authenticated()" rule (X-Service-Key required), consistent
// with /cart/add and /cart/deleteproduct - these are the admin dashboard's own calls.
@RestController
@RequestMapping("/wishlist")
public class WishlistController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public Wishlist addToWishlist(@RequestParam long phno, @RequestParam int productId) {
        return orderService.addToWishlist(phno, productId);
    }

    // Same self-service trust level as GET /wishlist/byphno - a customer adding to their OWN wishlist by their
    // OWN phone number, no internal X-Service-Key involved (see OrderController.checkout for the same reasoning
    // applied to placing an order). Not money-moving, so the risk of this being public is limited to someone
    // adding/removing entries on a phone number they don't own - the same limitation /wishlist/byphno itself
    // already has for reading one.
    @PostMapping("/self/add")
    public Wishlist addToOwnWishlist(@RequestParam long phno, @RequestParam int productId) {
        return orderService.addToWishlist(phno, productId);
    }

    @GetMapping("/byphno")
    public List<Wishlist> getWishlist(@RequestParam long phno) {
        return orderService.getWishlist(phno);
    }

    // Which wishlisted products have gotten cheaper since they were added - see
    // OrderService.getPriceDropAlerts() for why this is computed fresh rather than pushed anywhere.
    @GetMapping("/pricedrops")
    public List<WishlistPriceAlert> getPriceDropAlerts(@RequestParam long phno) {
        return orderService.getPriceDropAlerts(phno);
    }

    @DeleteMapping("/remove")
    public void removeFromWishlist(@RequestParam long phno, @RequestParam int productId) {
        orderService.removeFromWishlist(phno, productId);
    }

    // Same reasoning as /self/add above.
    @DeleteMapping("/self/remove")
    public void removeFromOwnWishlist(@RequestParam long phno, @RequestParam int productId) {
        orderService.removeFromWishlist(phno, productId);
    }
}
