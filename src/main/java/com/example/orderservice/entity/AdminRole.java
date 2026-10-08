package com.example.orderservice.entity;

/**
 * What a named admin may do on the dashboard, lowest to highest. Each role includes everything below it; which
 * endpoint needs which role is spelled out in security/AdminPolicy.
 * <ul>
 *   <li>SUPPORT - look customers and orders up, answer questions, handle support requests and order notes.
 *   No money movement and no configuration.</li>
 *   <li>MANAGER - everything operational: ship/deliver, cancel/return, coupons, pincodes, analytics, exports.</li>
 *   <li>OWNER - also admin accounts, the audit log, and manual credit/points adjustments.</li>
 * </ul>
 */
public enum AdminRole {
    SUPPORT, MANAGER, OWNER;

    public boolean atLeast(AdminRole other) {
        return compareTo(other) >= 0;
    }
}
