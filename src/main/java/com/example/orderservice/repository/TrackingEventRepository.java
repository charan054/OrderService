package com.example.orderservice.repository;

import com.example.orderservice.entity.TrackingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {
    List<TrackingEvent> findByOrderIdOrderByTimestampAsc(long orderId);

    List<TrackingEvent> findByStatusAndTimestampGreaterThanEqual(com.example.orderservice.entity.OrderStatus status, java.time.Instant since);
}
