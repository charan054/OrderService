package com.example.orderservice.repository;

import com.example.orderservice.entity.AccountDeletionCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface AccountDeletionCodeRepository extends JpaRepository<AccountDeletionCode, Long> {
    Optional<AccountDeletionCode> findByPhno(long phno);
    long deleteByExpiresAtBefore(Instant cutoff);
}
