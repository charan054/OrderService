package com.example.orderservice.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

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
    // The JSON name is "isDefault" - what both dashboards send and read. Lombok would otherwise derive "default" from
    // the isDefault()/setDefault() accessors, so a checkbox sent as isDefault was silently ignored and the default
    // never showed. The old "default" name is still accepted on input.
    // True once an order has shipped to this row and the customer edited or removed it: the row stays (old invoices, credit
    // notes and the GST place of supply read it) but is hidden and never offered again. System-managed.
    private boolean archived;
    @Getter(onMethod_ = @JsonProperty("isDefault"))
    @Setter(onMethod_ = {@JsonProperty("isDefault"), @JsonAlias("default")})
    private boolean isDefault;
}
