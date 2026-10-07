package com.example.orderservice.repository;

import com.example.orderservice.entity.OrderFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderFeedbackRepository extends JpaRepository<OrderFeedback, Long> {
    boolean existsByOrderId(long orderId);
    List<OrderFeedback> findByCustomerPhnoOrderByIdDesc(long phno);
    List<OrderFeedback> findAllByOrderByIdDesc(Pageable pageable);
}
