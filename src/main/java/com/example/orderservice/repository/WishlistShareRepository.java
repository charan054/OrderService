package com.example.orderservice.repository;

import com.example.orderservice.entity.WishlistShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WishlistShareRepository extends JpaRepository<WishlistShare, String> {
    Optional<WishlistShare> findByCustomerPhno(long phno);
    void deleteByCustomerPhno(long phno);
}
