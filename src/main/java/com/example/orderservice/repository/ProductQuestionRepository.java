package com.example.orderservice.repository;

import com.example.orderservice.entity.ProductQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductQuestionRepository extends JpaRepository<ProductQuestion, Long> {
    List<ProductQuestion> findByProductIdAndAnswerIsNotNullOrderByAnsweredAtDescIdDesc(int productId);
    List<ProductQuestion> findByAskerPhnoOrderByIdDesc(long phno);
    List<ProductQuestion> findByAnswerIsNullOrderByIdAsc();
    long countByAskerPhnoAndAnswerIsNull(long phno);
}
