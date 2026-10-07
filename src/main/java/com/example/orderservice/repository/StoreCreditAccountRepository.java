package com.example.orderservice.repository;

import com.example.orderservice.entity.StoreCreditAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreCreditAccountRepository extends JpaRepository<StoreCreditAccount, Long> {
    // Row lock for spending: two checkouts using the same credit at once must not both see the same balance.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StoreCreditAccount a where a.phno = :phno")
    Optional<StoreCreditAccount> lockByPhno(@Param("phno") long phno);
}
