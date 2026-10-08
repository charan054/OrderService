package com.example.orderservice.repository;

import com.example.orderservice.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    List<Subscription> findByCustomerPhnoOrderByIdDesc(long customerPhno);

    long countByCustomerPhnoAndStatusIn(long customerPhno, Collection<String> statuses);

    List<Subscription> findByStatusAndNextRunAtLessThanEqualOrderByIdAsc(String status, Instant now);

    List<Subscription> findAllByOrderByIdDesc();
}
