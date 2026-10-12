package com.example.orderservice.repository;

import com.example.orderservice.entity.RefundFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundFailureRepository extends JpaRepository<RefundFailure, Long> {
    List<RefundFailure> findByResolvedAtIsNullOrderByIdAsc();
}
