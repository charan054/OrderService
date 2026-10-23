package com.example.orderservice.repository;

import com.example.orderservice.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    // Unsettled attempts in one status that have sat there since before the cutoff.
    List<PaymentAttempt> findByStatusAndResolvedAtIsNullAndUpdatedAtBeforeOrderByIdAsc(PaymentAttempt.Status status, Instant cutoff);
}
