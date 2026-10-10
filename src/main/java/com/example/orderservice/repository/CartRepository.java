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
    long countByCustomerPhnoAndStatusIn(long phno, java.util.Collection<com.example.orderservice.entity.OrderStatus> statuses);
    long countByCustomerPhnoAndStatusAndPaymentMethodAndPaidFalse(long phno, com.example.orderservice.entity.OrderStatus status,
                                                                  com.example.orderservice.entity.PaymentMethod method);
}
