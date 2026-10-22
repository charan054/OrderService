package com.example.orderservice.repository;

import com.example.orderservice.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    public List<Cart> findBycustomerPhno(long phno);
    List<Cart> findByStatus(com.example.orderservice.entity.OrderStatus status);
    // invoiceDate in [from, to) - the GST report
    List<Cart> findByInvoiceDateGreaterThanEqualAndInvoiceDateLessThan(java.time.Instant from, java.time.Instant to);

    // Account deletion: orders that are still moving, or a delivered cash order whose payment was never recorded.
    long countByCustomerPhno(long phno);
    boolean existsByShippingAddressId(Long shippingAddressId);
    long countByDeliverySlotAndStatusIn(String deliverySlot, java.util.Collection<com.example.orderservice.entity.OrderStatus> statuses);
    long countByCustomerPhnoAndStatusIn(long phno, java.util.Collection<com.example.orderservice.entity.OrderStatus> statuses);
    long countByCustomerPhnoAndStatusAndPaymentMethodAndPaidFalse(long phno, com.example.orderservice.entity.OrderStatus status,
                                                                  com.example.orderservice.entity.PaymentMethod method);

    // UPI orders still awaiting payment past their deadline - the sweeper (or the buyer's next status check) should have
    // settled them. Oldest deadline first.
    List<Cart> findByStatusAndPaymentDeadlineBeforeOrderByPaymentDeadlineAsc(com.example.orderservice.entity.OrderStatus status,
                                                                             java.time.Instant before);

    // Other products bought in the same (non-cancelled) orders as productId, most often first. Rows are
    // [otherProductId, number of orders]; done in SQL so the public product page doesn't load every order ever placed.
    @org.springframework.data.jpa.repository.Query("""
            select b.productId, count(distinct c.orderId)
            from Cart c join c.orderItems a join c.orderItems b
            where a.productId = :productId and b.productId <> :productId
              and (c.status is null or c.status <> com.example.orderservice.entity.OrderStatus.CANCELLED)
            group by b.productId
            order by count(distinct c.orderId) desc, b.productId asc
            """)
    List<Object[]> findCoPurchasedProducts(@org.springframework.data.repository.query.Param("productId") int productId,
                                           org.springframework.data.domain.Pageable page);
}
