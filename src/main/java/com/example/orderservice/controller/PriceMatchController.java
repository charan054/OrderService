package com.example.orderservice.controller;

import com.example.orderservice.entity.PriceMatchRequest;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.PriceMatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// request and mine belong to the signed-in customer (or the service key); the open queue and resolve are admin-only
// (SecurityConfig default plus AdminPolicy, SUPPORT and up).
@RestController
@RequestMapping("/pricematch")
public class PriceMatchController {
    private final PriceMatchService service;

    public PriceMatchController(PriceMatchService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public PriceMatchRequest request(@RequestParam long phno, @RequestParam int productId,
                                     @RequestParam double competitorPrice, @RequestParam String competitorUrl) {
        CustomerAccess.requireSelfOrService(phno);
        return service.request(phno, productId, competitorPrice, competitorUrl);
    }

    @GetMapping("/mine")
    public List<PriceMatchRequest> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.mine(phno);
    }

    @GetMapping("/open")
    public List<PriceMatchRequest> open() {
        return service.open();
    }

    @PutMapping("/{id}/resolve")
    public PriceMatchRequest resolve(@PathVariable long id, @RequestParam boolean approved,
                                     @RequestParam(required = false) String note,
                                     @RequestParam(required = false) Double salePrice,
                                     @RequestParam(required = false) Integer saleDays) {
        return service.resolve(id, approved, note, salePrice, saleDays);
    }
}
