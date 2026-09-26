package com.example.orderservice.controller;

import com.example.orderservice.entity.Wishlist;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /wishlist/byphno is public (see SecurityConfig), the same self-service trust level as GET /cart/byphno -
// looking up your own list by your own phone number. Add/remove are mutations and stay under the default
// "anyRequest().authenticated()" rule (X-Service-Key required), consistent with /cart/add and /cart/deleteproduct.
@RestController
@RequestMapping("/wishlist")
public class WishlistController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public Wishlist addToWishlist(@RequestParam long phno, @RequestParam int productId) {
        return orderService.addToWishlist(phno, productId);
    }

    @GetMapping("/byphno")
    public List<Wishlist> getWishlist(@RequestParam long phno) {
        return orderService.getWishlist(phno);
    }

    @DeleteMapping("/remove")
    public void removeFromWishlist(@RequestParam long phno, @RequestParam int productId) {
        orderService.removeFromWishlist(phno, productId);
    }
}
