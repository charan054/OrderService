package com.example.orderservice.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;
@Data
@Table(name="cart")
@Entity
@JsonPropertyOrder({
        "orderId",
        "customerName",
        "customerPhno",
        "orderItems",
        "couponCode",
        "discountAmount",
        "pointsRedeemed",
        "totalPrice",
        "shippingAddressId",
        "deliveryNote",
        "deliverySlot",
        "paymentMethod",
        "paid",
        "status"
})
public class Cart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;
    private String customerName;
    private long customerPhno;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name="order_items_orderId")
    private List<OrderItem> orderItems;
    // Set by the caller at checkout (optional); order() validates it against the Coupon table and turns it into
    // discountAmount before charging. Kept on the saved order purely as a record of what was applied - re-editing
    // this field after the fact has no effect on anything.
    private String couponCode;
    private double discountAmount;
    // Set by the caller at checkout (optional); order() validates it against the customer's LoyaltyAccount
    // balance and turns it into an additional discount (1 point = ₹1) on top of any coupon, before charging.
    // Kept on the saved order as a record of how many points were applied, same role couponCode plays.
    private Integer pointsRedeemed;
    private double totalPrice;
    // How much of totalPrice no longer stands: refunded to the buyer for a PhonePe order, or no longer due for a
    // cash one. Grows with each per-item cancel/return and reaches totalPrice on a full cancel/return.
    private double refundedAmount;
    // Store credit the buyer chose to use at checkout (rupees, optional), taken off after coupon and points - see
    // OrderService.resolveStoreCredit. totalPrice is what was left to pay after it.
    private Double storeCreditUsed;
    // How much of storeCreditUsed has gone back to the customer's store credit through cancels/returns.
    private double storeCreditRefunded;
    // Where the refund made by the request that returned this order went: STORE_CREDIT, or null for the original
    // payment / nothing refunded. Response-only (used for the customer email and the storefront message), not stored.
    @Transient
    private String refundDestination;
    // Sequential tax-invoice number (PREFIX/2026-27/000123) and the date it was issued - see InvoiceNumberService.
    @Column(length = 30, unique = true)
    private String invoiceNumber;
    private Instant invoiceDate;
    // Set by the caller at checkout (optional); order() validates it belongs to the same customerPhno before
    // saving it as a record of which saved address the order shipped to. Purely informational once saved - like
    // couponCode, re-editing this field after the fact has no effect on anything.
    private Long shippingAddressId;
    // Optional free-text instruction from the buyer at checkout ("leave with security", ...). Trimmed and capped at
    // MAX_DELIVERY_NOTE_LENGTH by OrderService.order(); shown on the invoice and in the admin orders table/CSV.
    @Column(length = 200)
    private String deliveryNote;
    // Optional preferred delivery window (a key of OrderService.DELIVERY_SLOTS: MORNING/AFTERNOON/EVENING). A
    // request, not a guarantee - there is no courier scheduling behind it, so it's shown to whoever ships the order.
    @Column(length = 20)
    private String deliverySlot;
    // Defaults to PHONEPE when the caller doesn't set it, so existing callers that only ever paid through
    // PhonepayService (the admin dashboard, existing tests) keep working unchanged. CASH skips the PhonepayService
    // charge entirely in order() - see OrderService.order().
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private PaymentMethod paymentMethod = PaymentMethod.PHONEPE;
    // Set true by order() the moment a PHONEPE charge succeeds (money already moved); a CASH order starts false
    // and stays that way until OrderService.markPaid() records the cash actually being collected at delivery -
    // see that method for why this isn't just inferred from status==DELIVERED.
    private boolean paid;
    // columnDefinition pins this to a plain VARCHAR: Hibernate 7's default MySQL mapping for a STRING enum is a
    // native ENUM(...) column sized to whatever constants existed when the table was first created, so a later
    // OrderStatus addition (e.g. SHIPPED/DELIVERED) fails at runtime with "Data truncated for column 'status'"
    // since ddl-auto=update never widens an existing native enum's value list.
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private OrderStatus status = OrderStatus.PLACED;
    // The PhonepayService transaction that paid for this order (see OrderService.order()); needed to refund it
    // on cancellation. Null for orders placed before this field existed.
    private Long paymentTransactionId;
    // Set by OrderService.returnOrder() when a DELIVERED order is returned; null otherwise. Purely a record of
    // why, same role couponCode/shippingAddressId play - re-editing it after the fact has no effect.
    private String returnReason;
    // Why the buyer (or admin) cancelled the whole order: one of OrderService.CANCEL_REASONS, optional, plus an
    // optional free-text note. Null for an order that wasn't cancelled, or was cancelled without giving one.
    @Column(length = 40)
    private String cancelReason;
    // Who is carrying the parcel and how to follow it - recorded by whoever ships the order (optional, either can be
    // given alone) and shown to the buyer. Set via OrderService.ship()/updateShipment().
    @Column(length = 60)
    private String carrier;
    @Column(length = 60)
    private String trackingNumber;
    @Column(length = 200)
    private String cancelNote;
    // Set only for a PHONEPE order placed via a UPI collect request (see OrderService.order()'s payerUpiId
    // path) - the buyer's UPI ID as entered at checkout, purely a record like couponCode. Null for every other
    // order, including a PHONEPE order paid the older synchronous way (an already-supplied token, or
    // phone+PIN).
    private String upiId;
    // Set only while status is PENDING_PAYMENT - the deadline checkPendingPayment() enforces regardless of what
    // PhonepayService's own collect-request expiry says, so OrderService stays authoritative for how long an
    // order actually holds its reserved stock. Null once the order leaves PENDING_PAYMENT either way.
    private Instant paymentDeadline;

}
