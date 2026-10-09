package com.example.orderservice.repository;

import com.example.orderservice.entity.WishlistShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface WishlistShareRepository extends JpaRepository<WishlistShare, String> {
    Optional<WishlistShare> findByCustomerPhno(long phno);
    void deleteByCustomerPhno(long phno);

    // One atomic UPDATE, so two people opening the link at once are both counted.
    @Modifying
    @Transactional
    @Query("update WishlistShare s set s.viewCount = s.viewCount + 1 where s.token = :token")
    void incrementViewCount(@Param("token") String token);
}
