package com.example.orderservice.repository;

import com.example.orderservice.entity.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {
    List<CreditNote> findByOrderIdOrderByIdAsc(long orderId);

    // issuedAt in [from, to)
    List<CreditNote> findByIssuedAtGreaterThanEqualAndIssuedAtLessThanOrderByIdAsc(Instant from, Instant to);
}
