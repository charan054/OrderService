package com.example.orderservice.repository;

import com.example.orderservice.entity.SavedCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SavedCartRepository extends JpaRepository<SavedCart, Long> {
    java.util.List<SavedCart> findByUpdatedAtBetweenAndReminderSentAtIsNull(java.time.Instant from, java.time.Instant to);
}
