package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

// One pincode the store delivers to, with how many days delivery takes there. Managed by the admin
// (X-Service-Key); while this table is empty every pincode counts as serviceable, so a fresh install keeps taking
// orders until the admin decides where it actually ships.
@Data
@Entity
@Table(name = "serviceable_pincode")
public class ServiceablePincode {
    @Id
    private String pincode;
    private int deliveryDays;
}
