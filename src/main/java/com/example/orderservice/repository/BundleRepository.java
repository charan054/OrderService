package com.example.orderservice.repository;

import com.example.orderservice.entity.Bundle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BundleRepository extends JpaRepository<Bundle, Long> {
    List<Bundle> findByActiveTrueOrderByIdAsc();
}
