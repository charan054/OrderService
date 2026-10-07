package com.example.orderservice.repository;

import com.example.orderservice.entity.CustomerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {
    java.util.Optional<CustomerAccount> findByReferralCode(String referralCode);
}
