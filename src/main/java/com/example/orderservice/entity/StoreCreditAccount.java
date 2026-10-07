package com.example.orderservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

// One customer's store credit balance in rupees (see StoreCreditService). Never negative; every change to it has a
// matching StoreCreditTransaction row.
@Data
@Entity
@Table(name = "store_credit_account")
public class StoreCreditAccount {
    @Id
    private long phno;
    private double balance;
    private Instant updatedAt;
}
