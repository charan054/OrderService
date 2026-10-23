package com.example.orderservice.repository;

import com.example.orderservice.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByCustomerPhno(long customerPhno);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    long deleteByEndpointAndCustomerPhno(String endpoint, long customerPhno);
    long deleteByCustomerPhno(long customerPhno);
}
