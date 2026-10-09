package com.example.orderservice.controller;

import com.example.orderservice.dto.PincodeImportResult;
import com.example.orderservice.dto.PincodeServiceability;
import com.example.orderservice.entity.ServiceablePincode;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /check and /slots are public (SecurityConfig) - the storefront asks them before the customer has even signed in;
// the rest fall under the default service-only rule.
@RestController
@RequestMapping("/pincodes")
public class PincodeController {
    @Autowired
    private OrderService orderService;

    @GetMapping("/check")
    public PincodeServiceability check(@RequestParam String pincode) {
        return orderService.checkPincode(pincode);
    }

    @GetMapping("/slots")
    public List<String> slots() {
        return orderService.getDeliverySlots();
    }

    @PostMapping("/add")
    public ServiceablePincode add(@RequestBody ServiceablePincode pincode) {
        return orderService.savePincode(pincode);
    }

    // Admin: CSV text body, one pincode,deliveryDays[,city,state] per line.
    @PostMapping(value = "/import", consumes = "text/plain")
    public PincodeImportResult importCsv(@RequestBody String csv) {
        return orderService.importPincodes(csv);
    }

    @DeleteMapping("/remove")
    public void remove(@RequestParam String pincode) {
        orderService.removePincode(pincode);
    }

    @GetMapping("/all")
    public List<ServiceablePincode> all() {
        return orderService.getPincodes();
    }
}
