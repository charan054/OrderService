package com.example.orderservice.controller;

import com.example.orderservice.dto.SalesBreakdown;
import com.example.orderservice.service.SalesBreakdownService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// Admin only like the other /cart/analytics endpoints: no public rule, so the service key (or a MANAGER/OWNER admin).
// Dates are yyyy-MM-dd, inclusive (default the last 30 days); zone is an IANA name (default UTC).
@RestController
@RequestMapping("/cart/analytics/breakdown")
public class SalesBreakdownController {
    private final SalesBreakdownService service;

    public SalesBreakdownController(SalesBreakdownService service) {
        this.service = service;
    }

    @GetMapping
    public SalesBreakdown breakdown(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String zone,
                                    @RequestParam(required = false) String groupBy,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String dir,
                                    @RequestParam(required = false) Integer limit) {
        return service.breakdown(from, to, zone, groupBy, sort, dir, limit);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String zone,
                                         @RequestParam(required = false) String groupBy,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) String dir) {
        String name = "category".equalsIgnoreCase(groupBy == null ? "" : groupBy.trim()) ? "category" : "product";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"sales-by-" + name + ".csv\"")
                .body(service.exportCsv(from, to, zone, groupBy, sort, dir));
    }
}
