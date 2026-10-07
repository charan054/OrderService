package com.example.orderservice.repository;

import com.example.orderservice.entity.StoreCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoreCreditTransactionRepository extends JpaRepository<StoreCreditTransaction, Long> {
    List<StoreCreditTransaction> findTop50ByPhnoOrderByIdDesc(long phno);
}
