package com.example.orderservice.repository;

import com.example.orderservice.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    public List<Cart> findBycustomerPhno(long phno);
    List<Cart> findByStatus(com.example.orderservice.entity.OrderStatus status);
}
