package com.example.orderservice.repository;

import com.example.orderservice.entity.ReorderReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReorderReminderRepository extends JpaRepository<ReorderReminder, Long> {
    List<ReorderReminder> findByCustomerPhno(long phno);

    Optional<ReorderReminder> findByCustomerPhnoAndProductId(long phno, int productId);
}
