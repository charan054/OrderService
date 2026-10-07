package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.PublicQuestion;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.ProductQuestionRepository;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Product Q&A: customers ask, the admin answers, and only answered questions are public. Kept apart from
 * OrderService (already ~2000 lines) - it only needs the catalog client to check the product exists.
 */
@Service
public class ProductQuestionService {
    static final int MIN_QUESTION_LENGTH = 5;
    static final int MAX_QUESTION_LENGTH = 500;
    static final int MAX_ANSWER_LENGTH = 1000;
    // Unanswered questions one customer may have open at once - stops one phone number flooding the admin queue.
    static final int MAX_PENDING_PER_CUSTOMER = 5;

    private final ProductQuestionRepository questions;
    private final ProductClient productClient;
    private final Clock clock;

    public ProductQuestionService(ProductQuestionRepository questions, ProductClient productClient, Clock clock) {
        this.questions = questions;
        this.productClient = productClient;
        this.clock = clock;
    }

    public ProductQuestion ask(long phno, int productId, String text) {
        validatePhno(phno);
        String question = text == null ? "" : text.trim();
        if (question.length() < MIN_QUESTION_LENGTH || question.length() > MAX_QUESTION_LENGTH) {
            throw new ProductException("Question must be " + MIN_QUESTION_LENGTH + " to " + MAX_QUESTION_LENGTH + " characters");
        }
        Product product;
        try {
            product = productClient.getProductById(productId);
        } catch (FeignException e) {
            throw new ProductException("Product not found");
        }
        if (product == null) {
            throw new ProductException("Product not found");
        }
        if (questions.countByAskerPhnoAndAnswerIsNull(phno) >= MAX_PENDING_PER_CUSTOMER) {
            throw new ProductException("You already have " + MAX_PENDING_PER_CUSTOMER
                    + " unanswered questions - please wait for answers before asking more");
        }
        ProductQuestion q = new ProductQuestion();
        q.setProductId(productId);
        q.setAskerPhno(phno);
        q.setQuestion(question);
        q.setCreatedAt(Instant.now(clock));
        return questions.save(q);
    }

    public List<PublicQuestion> answeredFor(int productId) {
        return questions.findByProductIdAndAnswerIsNotNullOrderByAnsweredAtDescIdDesc(productId).stream()
                .map(q -> new PublicQuestion(q.getId(), q.getQuestion(), q.getAnswer(), q.getAnsweredAt()))
                .toList();
    }

    public List<ProductQuestion> mine(long phno) {
        validatePhno(phno);
        return questions.findByAskerPhnoOrderByIdDesc(phno);
    }

    public List<ProductQuestion> pending() {
        return questions.findByAnswerIsNullOrderByIdAsc();
    }

    // Answering again overwrites the earlier answer (an admin correcting a mistake).
    public ProductQuestion answer(long id, String text) {
        String answer = text == null ? "" : text.trim();
        if (answer.isEmpty() || answer.length() > MAX_ANSWER_LENGTH) {
            throw new ProductException("Answer must be 1 to " + MAX_ANSWER_LENGTH + " characters");
        }
        ProductQuestion q = questions.findById(id).orElseThrow(() -> new ProductException("Question not found"));
        q.setAnswer(answer);
        q.setAnsweredAt(Instant.now(clock));
        return questions.save(q);
    }

    public void delete(long id) {
        if (!questions.existsById(id)) {
            throw new ProductException("Question not found");
        }
        questions.deleteById(id);
    }

    private static void validatePhno(long phno) {
        String x = String.valueOf(phno);
        if (x.length() != 10 || !x.matches("^[6-9].*")) {
            throw new ProductException("Invalid mobile number");
        }
    }
}
