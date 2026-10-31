package com.example.orderservice.repository;

import com.example.orderservice.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
    List<WebhookDelivery> findByStateAndNextAttemptAtLessThanEqualOrderByIdAsc(String state, Instant now, Pageable page);

    List<WebhookDelivery> findByEndpointIdOrderByIdDesc(long endpointId, Pageable page);

    @Modifying
    @Transactional
    @Query("delete from WebhookDelivery d where d.endpointId = :endpointId")
    void deleteByEndpointId(@Param("endpointId") long endpointId);

    @Modifying
    @Transactional
    @Query("delete from WebhookDelivery d where d.state <> 'PENDING' and d.occurredAt < :before")
    int deleteFinishedBefore(@Param("before") Instant before);
}
