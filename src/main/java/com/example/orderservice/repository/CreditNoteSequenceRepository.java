package com.example.orderservice.repository;

import com.example.orderservice.entity.CreditNoteSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditNoteSequenceRepository extends JpaRepository<CreditNoteSequence, String> {
    // Row lock so two credit notes issued at the same moment can never get the same number.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreditNoteSequence s where s.fiscalYear = :fy")
    Optional<CreditNoteSequence> lockByFiscalYear(@Param("fy") String fiscalYear);
}
