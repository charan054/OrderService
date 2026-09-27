package com.example.orderservice.controller;

import com.example.orderservice.entity.ShippingAddress;
import com.example.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /addresses/byphno is public (see SecurityConfig), the same self-service trust level as GET /cart/byphno -
// looking up your own saved addresses by your own phone number. Add/remove are mutations and stay under the
// default "anyRequest().authenticated()" rule (X-Service-Key required), consistent with /wishlist/add and
// /wishlist/remove.
@RestController
@RequestMapping("/addresses")
public class ShippingAddressController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public ShippingAddress addAddress(@RequestBody ShippingAddress address) {
        return orderService.saveAddress(address);
    }

    // Same self-service trust level as GET /addresses/byphno - a customer managing their OWN saved addresses by
    // their OWN phone number, no internal X-Service-Key involved (same reasoning as /wishlist/self/add).
    @PostMapping("/self/add")
    public ShippingAddress addOwnAddress(@RequestBody ShippingAddress address) {
        return orderService.saveAddress(address);
    }

    @GetMapping("/byphno")
    public List<ShippingAddress> getAddresses(@RequestParam long phno) {
        return orderService.getAddresses(phno);
    }

    @DeleteMapping("/remove")
    public void removeAddress(@RequestParam long phno, @RequestParam long addressId) {
        orderService.deleteAddress(phno, addressId);
    }

    // Same reasoning as /self/add above.
    @DeleteMapping("/self/remove")
    public void removeOwnAddress(@RequestParam long phno, @RequestParam long addressId) {
        orderService.deleteAddress(phno, addressId);
    }
}
