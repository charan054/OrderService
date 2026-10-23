package com.example.orderservice.repository;

import com.example.orderservice.entity.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {
    List<DeliveryAttempt> findByOrderIdOrderByAttemptNoAsc(long orderId);

    long countByOrderId(long orderId);

    List<DeliveryAttempt> findByCreatedAtGreaterThanEqual(java.time.Instant since);
}
