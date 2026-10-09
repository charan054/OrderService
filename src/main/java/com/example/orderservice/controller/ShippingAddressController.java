package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /byphno and /self/* are the storefront's: a signed-in customer (own phone number only, see CustomerAccess) or
// the service key. /add and /remove are the admin dashboard's and need the X-Service-Key.
@RestController
@RequestMapping("/addresses")
public class ShippingAddressController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public ShippingAddress addAddress(@RequestBody ShippingAddress address) {
        return orderService.saveAddress(address);
    }

    // A signed-in customer managing their own saved addresses - no X-Service-Key needed.
    @PostMapping("/self/add")
    public ShippingAddress addOwnAddress(@RequestBody ShippingAddress address) {
        CustomerAccess.requireSelfOrService(address.getCustomerPhno());
        return orderService.saveAddress(address);
    }

    // Make one of the caller's own saved addresses the default (see OrderService.setDefaultAddress).
    @PutMapping("/self/default")
    public ShippingAddress makeOwnAddressDefault(@RequestParam long phno, @RequestParam long addressId) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.setDefaultAddress(phno, addressId);
    }

    @GetMapping("/byphno")
    public List<ShippingAddress> getAddresses(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return orderService.getAddresses(phno);
    }

    @DeleteMapping("/remove")
    public void removeAddress(@RequestParam long phno, @RequestParam long addressId) {
        orderService.deleteAddress(phno, addressId);
    }

    // Same reasoning as /self/add above.
    @DeleteMapping("/self/remove")
    public void removeOwnAddress(@RequestParam long phno, @RequestParam long addressId) {
        CustomerAccess.requireSelfOrService(phno);
        orderService.deleteAddress(phno, addressId);
    }
}
