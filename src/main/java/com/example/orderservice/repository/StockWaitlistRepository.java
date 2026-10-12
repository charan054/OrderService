package com.example.orderservice.repository;

import com.example.orderservice.entity.StockWaitlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockWaitlistRepository extends JpaRepository<StockWaitlist, Long> {
    List<StockWaitlist> findByCustomerPhno(long phno);
    Optional<StockWaitlist> findByCustomerPhnoAndProductId(long phno, int productId);
    long countByProductId(int productId);
    long countByProductIdAndHeldUntilAfterAndCustomerPhnoNot(int productId, java.time.Instant now, long customerPhno);
    long countByProductIdAndHeldUntilAfter(int productId, java.time.Instant now);
    void deleteByCustomerPhnoAndProductId(long phno, int productId);
}
