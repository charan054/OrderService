package com.example.orderservice.repository;

import com.example.orderservice.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {
    Optional<CouponRedemption> findByCouponCodeAndCustomerPhno(String couponCode, long customerPhno);
}
