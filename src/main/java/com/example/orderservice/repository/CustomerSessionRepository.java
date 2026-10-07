package com.example.orderservice.repository;

import com.example.orderservice.entity.CustomerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface CustomerSessionRepository extends JpaRepository<CustomerSession, Long> {
    Optional<CustomerSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteByPhno(long phno);
    long deleteByExpiresAtBefore(Instant cutoff);
}
