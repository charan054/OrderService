package com.example.orderservice.repository;

import com.example.orderservice.entity.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, String> {
    // Row lock so two orders placed at the same moment can never get the same number.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceSequence s where s.fiscalYear = :fy")
    Optional<InvoiceSequence> lockByFiscalYear(@Param("fy") String fiscalYear);
}
