package com.example.orderservice.repository;

import com.example.orderservice.entity.MissedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MissedSearchRepository extends JpaRepository<MissedSearch, Long> {
    Optional<MissedSearch> findByTerm(String term);

    List<MissedSearch> findTop50ByOrderByTimesDescLastSearchedAtDesc();
}
