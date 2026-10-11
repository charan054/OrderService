package com.example.orderservice.controller;

import com.example.orderservice.entity.SearchWatch;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.SearchWatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The storefront's "tell me when you stock this" for searches that found nothing: a signed-in customer's own
// number only (see CustomerAccess), or the service key.
@RestController
@RequestMapping("/searchwatch")
public class SearchWatchController {
    private final SearchWatchService service;

    public SearchWatchController(SearchWatchService service) {
        this.service = service;
    }

    @PostMapping("/self/add")
    public SearchWatch add(@RequestParam long phno, @RequestParam String term) {
        CustomerAccess.requireSelfOrService(phno);
        return service.add(phno, term);
    }

    @GetMapping("/byphno")
    public List<SearchWatch> list(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.list(phno);
    }

    @DeleteMapping("/self/remove")
    public void remove(@RequestParam long phno, @RequestParam String term) {
        CustomerAccess.requireSelfOrService(phno);
        service.remove(phno, term);
    }
}
