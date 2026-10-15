package com.example.orderservice.repository;

import com.example.orderservice.entity.PriceMatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceMatchRequestRepository extends JpaRepository<PriceMatchRequest, Long> {
    List<PriceMatchRequest> findByCustomerPhnoOrderByIdDesc(long phno);
    List<PriceMatchRequest> findByStatusOrderByIdAsc(PriceMatchRequest.Status status);
    long countByStatus(PriceMatchRequest.Status status);
    long countByCustomerPhnoAndStatus(long phno, PriceMatchRequest.Status status);
    boolean existsByCustomerPhnoAndProductIdAndStatus(long phno, int productId, PriceMatchRequest.Status status);
}
