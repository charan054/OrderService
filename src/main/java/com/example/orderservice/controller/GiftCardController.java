package com.example.orderservice.controller;

import com.example.orderservice.dto.GiftCardDtos.GiftCardList;
import com.example.orderservice.dto.GiftCardDtos.GiftCardView;
import com.example.orderservice.dto.GiftCardDtos.MintRequest;
import com.example.orderservice.dto.GiftCardDtos.MintResult;
import com.example.orderservice.dto.GiftCardDtos.RedeemRequest;
import com.example.orderservice.dto.GiftCardDtos.RedeemResult;
import com.example.orderservice.security.AdminPrincipal;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.GiftCardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Redeeming is the signed-in customer's own (or the service key's for anyone). Minting, listing and voiding hand out or
// account for money-like value, so they are owner-only (AdminPolicy) or the service key. Codes only ever travel in
// request/response bodies, never in a URL.
@RestController
@RequestMapping("/giftcards")
public class GiftCardController {
    private final GiftCardService giftCardService;

    public GiftCardController(GiftCardService giftCardService) {
        this.giftCardService = giftCardService;
    }

    @PostMapping("/redeem")
    public RedeemResult redeem(@RequestBody RedeemRequest request) {
        CustomerAccess.requireSelfOrService(request.phno());
        return giftCardService.redeem(request.phno(), request.code());
    }

    @PostMapping("/mint")
    public MintResult mint(@RequestBody MintRequest request, Authentication authentication) {
        String actor = authentication.getPrincipal() instanceof AdminPrincipal admin ? admin.username() : "service-key";
        return giftCardService.mint(request, actor);
    }

    @GetMapping
    public GiftCardList list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return giftCardService.list(status, limit);
    }

    @PutMapping("/{id}/void")
    public GiftCardView voidCard(@PathVariable long id) {
        return giftCardService.voidCard(id);
    }
}
