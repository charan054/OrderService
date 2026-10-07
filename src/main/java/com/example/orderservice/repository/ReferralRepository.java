package com.example.orderservice.repository;

import com.example.orderservice.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, Long> {
    Optional<Referral> findByRefereePhno(long refereePhno);

    List<Referral> findByReferrerPhno(long referrerPhno);

    long countByReferrerPhnoAndRewardedTrue(long referrerPhno);
}
