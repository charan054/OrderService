package com.example.orderservice.repository;

import com.example.orderservice.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, Long> {
    List<Wishlist> findByCustomerPhno(long phno);
    Optional<Wishlist> findByCustomerPhnoAndProductId(long phno, int productId);
    void deleteByCustomerPhnoAndProductId(long phno, int productId);
}
