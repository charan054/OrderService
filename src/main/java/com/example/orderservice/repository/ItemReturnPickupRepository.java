package com.example.orderservice.repository;

import com.example.orderservice.entity.ItemReturnPickup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ItemReturnPickupRepository extends JpaRepository<ItemReturnPickup, Long> {
    List<ItemReturnPickup> findByStatusInOrderByIdAsc(Collection<String> statuses);

    List<ItemReturnPickup> findByOrderIdAndStatusIn(long orderId, Collection<String> statuses);
}
