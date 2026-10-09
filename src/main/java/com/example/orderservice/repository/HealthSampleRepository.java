package com.example.orderservice.repository;

import com.example.orderservice.entity.HealthSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface HealthSampleRepository extends JpaRepository<HealthSample, Long> {
    List<HealthSample> findByCheckedAtGreaterThanEqualOrderByCheckedAtAscIdAsc(Instant since);

    @Modifying
    @Transactional
    @Query("delete from HealthSample s where s.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
