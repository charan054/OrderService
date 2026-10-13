package com.example.orderservice.repository;

import com.example.orderservice.entity.ExchangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ExchangeRequestRepository extends JpaRepository<ExchangeRequest, Long> {
    List<ExchangeRequest> findByOrderIdOrderByIdAsc(long orderId);

    List<ExchangeRequest> findByOrderIdAndStatusIn(long orderId, Collection<String> statuses);

    List<ExchangeRequest> findByStatusInOrderByIdAsc(Collection<String> statuses);
}
