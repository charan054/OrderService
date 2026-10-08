package com.example.orderservice.repository;

import com.example.orderservice.entity.AdminLoginEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AdminLoginEventRepository extends JpaRepository<AdminLoginEvent, Long> {
    List<AdminLoginEvent> findAllByOrderByIdDesc(Pageable pageable);
    List<AdminLoginEvent> findByUsernameOrderByIdDesc(String username, Pageable pageable);
    long deleteByAtBefore(Instant cutoff);
}
