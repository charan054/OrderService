package com.example.orderservice.repository;

import com.example.orderservice.entity.CustomerLoginCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface CustomerLoginCodeRepository extends JpaRepository<CustomerLoginCode, Long> {
    Optional<CustomerLoginCode> findByPhno(long phno);
    void deleteByPhno(long phno);
    long deleteByExpiresAtBefore(Instant cutoff);
}
