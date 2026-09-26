package com.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Data;

// A customer's saved delivery address, looked up by their own phone number the same way Cart/Wishlist are -
// this system has no login, so a valid Indian mobile number is the only identity check there is (see
// OrderService.validatePhno()). A customer can save several (label distinguishes "Home"/"Work"/...); at most
// one may be the default at a time, enforced in OrderService.saveAddress().
@Data
@Table(name = "shipping_address")
@Entity
public class ShippingAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long customerPhno;
    private String label;
    private String line1;
    private String line2;
    private String city;
    private String state;
    private String pincode;
    private boolean isDefault;
}
