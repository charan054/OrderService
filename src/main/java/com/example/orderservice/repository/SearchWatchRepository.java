package com.example.orderservice.repository;

import com.example.orderservice.entity.SearchWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SearchWatchRepository extends JpaRepository<SearchWatch, Long> {
    List<SearchWatch> findByCustomerPhnoOrderByIdDesc(long phno);

    Optional<SearchWatch> findByCustomerPhnoAndTerm(long phno, String term);

    long countByCustomerPhno(long phno);

    List<SearchWatch> findByNotifiedAtIsNull();
}
