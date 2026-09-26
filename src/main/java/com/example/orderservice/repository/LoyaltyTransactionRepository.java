package com.example.orderservice.repository;

import com.example.orderservice.entity.LoyaltyTransaction;
import com.example.orderservice.entity.LoyaltyTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {
    List<LoyaltyTransaction> findByCustomerPhnoOrderByTimestampDesc(long phno);

    // Used to claw back exactly what an order actually earned (see OrderService.clawBackLoyaltyPoints()) rather
    // than recomputing it, since the tier multiplier in effect at delivery time may differ from the multiplier
    // in effect at return time.
    Optional<LoyaltyTransaction> findByOrderIdAndType(Long orderId, LoyaltyTransactionType type);
}
