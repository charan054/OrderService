package com.example.orderservice.controller;

import com.example.orderservice.dto.ReasonsReport;
import com.example.orderservice.service.ReasonsReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

// Admin only (service key, the default rule for /cart/analytics/**). Dates are yyyy-MM-dd, inclusive; default: the last 90 days.
@RestController
@RequestMapping("/cart/analytics/reasons")
public class ReasonsReportController {
    private final ReasonsReportService service;

    public ReasonsReportController(ReasonsReportService service) {
        this.service = service;
    }

    @GetMapping
    public ReasonsReport report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReasonsReport report = service.report(from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"returns-and-cancellations.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.csv(report).getBytes(StandardCharsets.UTF_8));
    }
}
