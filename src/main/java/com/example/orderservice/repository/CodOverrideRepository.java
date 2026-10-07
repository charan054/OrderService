package com.example.orderservice.repository;

import com.example.orderservice.entity.CodOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CodOverrideRepository extends JpaRepository<CodOverride, Long> {
}
