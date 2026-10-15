package com.example.orderservice.repository;

import com.example.orderservice.entity.QuantityBreak;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuantityBreakRepository extends JpaRepository<QuantityBreak, Long> {
    List<QuantityBreak> findByActiveTrueOrderByProductIdAscMinQuantityAsc();
    boolean existsByProductIdAndMinQuantityAndActiveTrue(int productId, int minQuantity);
}
