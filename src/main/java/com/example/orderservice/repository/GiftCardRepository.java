package com.example.orderservice.repository;

import com.example.orderservice.entity.GiftCard;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GiftCardRepository extends JpaRepository<GiftCard, Long> {
    Optional<GiftCard> findByCodeHash(String codeHash);

    List<GiftCard> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * Marks the card redeemed by this phone, but only if it still can be: unused, not voided, not expired. One
     * statement, so two people redeeming the same code at once can't both win - the loser gets 0 rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update GiftCard g set g.redeemedBy = :phno, g.redeemedAt = :now "
            + "where g.id = :id and g.redeemedAt is null and g.voided = false and (g.expiresAt is null or g.expiresAt > :now)")
    int claim(@Param("id") long id, @Param("phno") long phno, @Param("now") Instant now);
}
