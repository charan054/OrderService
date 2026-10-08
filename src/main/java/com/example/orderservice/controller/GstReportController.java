package com.example.orderservice.controller;

import com.example.orderservice.service.GstReportPdfService;
import com.example.orderservice.service.GstReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

// Service key or a MANAGER/OWNER admin (the default rule; see AdminPolicy). Dates are yyyy-MM-dd, inclusive, in the
// store's time zone; the default range is the current month so far.
@RestController
@RequestMapping("/gst")
public class GstReportController {
    private final GstReportService reportService;
    private final GstReportPdfService pdfService;
    private final Clock clock;

    public GstReportController(GstReportService reportService, GstReportPdfService pdfService, Clock clock) {
        this.reportService = reportService;
        this.pdfService = pdfService;
        this.clock = clock;
    }

    @GetMapping("/report")
    public GstReportService.Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = reportService.today(clock.instant());
        return reportService.report(from != null ? from : today.withDayOfMonth(1), to != null ? to : today);
    }

    @GetMapping(value = "/report/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = reportService.today(clock.instant());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"gst-report.csv\"")
                .body(reportService.exportCsv(from != null ? from : today.withDayOfMonth(1), to != null ? to : today));
    }

    // The same report as a PDF to hand to an accountant.
    @GetMapping(value = "/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = reportService.today(clock.instant());
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"gst-report-" + start + "-to-" + end + ".pdf\"")
                .body(pdfService.render(reportService.report(start, end)));
    }
}
