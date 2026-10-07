package com.example.orderservice.controller;

import com.example.orderservice.dto.CustomerDataExport;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.CustomerDataExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The signed-in customer's OWN data only (or the service key) - see CustomerAccess. Sent as a file download.
@RestController
public class CustomerDataExportController {
    @Autowired
    private CustomerDataExportService service;

    @GetMapping("/customer/export")
    public ResponseEntity<CustomerDataExport> export(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("my-data-" + phno + ".json").build().toString())
                .body(service.export(phno));
    }
}
