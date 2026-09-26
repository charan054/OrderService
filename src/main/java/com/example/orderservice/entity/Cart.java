package com.example.orderservice.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

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
        "paymentMethod",
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
    // Set by the caller at checkout (optional); order() validates it belongs to the same customerPhno before
    // saving it as a record of which saved address the order shipped to. Purely informational once saved - like
    // couponCode, re-editing this field after the fact has no effect on anything.
    private Long shippingAddressId;
    // Defaults to PHONEPE when the caller doesn't set it, so existing callers that only ever paid through
    // PhonepayService (the admin dashboard, existing tests) keep working unchanged. CASH skips the PhonepayService
    // charge entirely in order() - see OrderService.order().
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private PaymentMethod paymentMethod = PaymentMethod.PHONEPE;
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

}
