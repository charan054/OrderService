package com.example.orderservice.repository;

import com.example.orderservice.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {
    // Accounts holding points whose last activity falls in [from, to] - i.e. the ones about to expire.
    java.util.List<LoyaltyAccount> findByPointsBalanceGreaterThanAndLastActivityAtBetween(
            int pointsBalance, java.time.Instant from, java.time.Instant to);
}
