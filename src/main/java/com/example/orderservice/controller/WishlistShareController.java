package com.example.orderservice.controller;

import com.example.orderservice.dto.SharedWishlistItem;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.WishlistShareService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// /share is the signed-in customer's own (see CustomerAccess); /shared/{token} is public - the unguessable token is
// the credential, and it exposes only product details, never the owner's phone number.
@RestController
@RequestMapping("/wishlist")
public class WishlistShareController {
    private final WishlistShareService service;

    public WishlistShareController(WishlistShareService service) {
        this.service = service;
    }

    @PostMapping("/share")
    public Map<String, String> share(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return Map.of("token", service.getOrCreateToken(phno));
    }

    // Never creates a link just by asking: {"shared": false} when there is none.
    @GetMapping("/share")
    public Map<String, Object> shareStatus(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.findLink(phno)
                .<Map<String, Object>>map(link -> Map.of("shared", true, "token", link.getToken(), "views", link.getViewCount()))
                .orElse(Map.of("shared", false));
    }

    @DeleteMapping("/share")
    public void revoke(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        service.revoke(phno);
    }

    @GetMapping("/shared/{token}")
    public List<SharedWishlistItem> view(@PathVariable String token) {
        return service.view(token);
    }
}
