package com.example.orderservice.dto;

import java.util.List;

// What deleting this account would do, shown before a code is ever sent. canDelete=false lists why in blockers.
// loyaltyPoints and storeCredit are lost for good, which is why confirming them is a separate, explicit step.
public record AccountDeletionPreview(String email, boolean canDelete, List<String> blockers, int loyaltyPoints,
                                     double storeCredit, long activeSubscriptions, long orders) {
}
