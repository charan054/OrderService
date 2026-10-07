package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.dto.WishlistPriceAlert;
import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /byphno, /pricedrops and /self/* are the storefront's: a signed-in customer (own phone number only, see
// CustomerAccess) or the service key. /add and /remove are the admin dashboard's and need the X-Service-Key.
@RestController
@RequestMapping("/wishlist")
public class WishlistController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public Wishlist addToWishlist(@RequestParam long phno, @RequestParam int productId) {
        return orderService.addToWishlist(phno, productId);
    }

    // A signed-in customer adding to their own wishlist - no X-Service-Key needed.
    @PostMapping("/self/add")
    public Wishlist addToOwnWishlist(@RequestParam long phno, @RequestParam int productId) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.addToWishlist(phno, productId);
    }

    @GetMapping("/byphno")
    public List<Wishlist> getWishlist(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getWishlist(phno);
    }

    // Which wishlisted products have gotten cheaper since they were added - see
    // OrderService.getPriceDropAlerts() for why this is computed fresh rather than pushed anywhere.
    @GetMapping("/pricedrops")
    public List<WishlistPriceAlert> getPriceDropAlerts(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getPriceDropAlerts(phno);
    }

    @DeleteMapping("/remove")
    public void removeFromWishlist(@RequestParam long phno, @RequestParam int productId) {
        orderService.removeFromWishlist(phno, productId);
    }

    // Same reasoning as /self/add above.
    @DeleteMapping("/self/remove")
    public void removeFromOwnWishlist(@RequestParam long phno, @RequestParam int productId) {
        CustomerAccess.requireSelfOrService(phno);
        orderService.removeFromWishlist(phno, productId);
    }
}
