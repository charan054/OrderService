package com.example.orderservice.controller;

import com.example.orderservice.service.TestDataCleanupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Owner-only (everything under /admin/** is - see AdminPolicy) and off unless testdata.cleanup-enabled=true.
@RestController
@RequestMapping("/admin/testdata")
public class TestDataController {
    private final TestDataCleanupService service;

    public TestDataController(TestDataCleanupService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public TestDataCleanupService.Preview preview() {
        return service.preview();
    }

    // A dry run unless confirm=true is passed explicitly.
    @PostMapping("/cleanup")
    public TestDataCleanupService.Outcome cleanup(@RequestParam(defaultValue = "false") boolean confirm) {
        return service.run(!confirm);
    }
}
