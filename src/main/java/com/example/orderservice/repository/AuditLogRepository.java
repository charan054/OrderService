package com.example.orderservice.repository;

import com.example.orderservice.entity.AuditLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {
    List<AuditLogEntry> findAllByOrderByIdDesc(Pageable pageable);
    List<AuditLogEntry> findByPathContainingIgnoreCaseOrderByIdDesc(String path, Pageable pageable);
    List<AuditLogEntry> findByActorIgnoreCaseOrderByIdDesc(String actor, Pageable pageable);
    List<AuditLogEntry> findByActorIgnoreCaseAndPathContainingIgnoreCaseOrderByIdDesc(String actor, String path, Pageable pageable);
}
