package com.example.orderservice.repository;

import com.example.orderservice.entity.OrderShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderShareLinkRepository extends JpaRepository<OrderShareLink, String> {
    List<OrderShareLink> findByOrderIdAndRevokedAtIsNull(long orderId);
}
