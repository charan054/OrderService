package com.example.orderservice.dto;

// Answer to GET /pincodes/check. deliveryDays is null when the pincode isn't serviceable, or when the admin hasn't
// configured any pincodes yet (everything is then accepted, with no estimate to give).
public record PincodeServiceability(String pincode, boolean serviceable, Integer deliveryDays) {
}
