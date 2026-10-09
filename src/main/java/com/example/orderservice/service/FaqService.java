package com.example.orderservice.service;

import com.example.orderservice.entity.Faq;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.FaqRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** The storefront Help page: a short, admin-editable list of questions and answers. */
@Service
public class FaqService {
    static final int MAX_QUESTION = 200;
    static final int MAX_ANSWER = 2000;
    static final int MAX_ENTRIES = 100;

    private final FaqRepository faqs;
    private final Clock clock;

    public FaqService(FaqRepository faqs, Clock clock) {
        this.faqs = faqs;
        this.clock = clock;
    }

    public List<Faq> list() {
        return faqs.findAllByOrderBySortOrderAscIdAsc();
    }

    // Creates a new entry, or edits the existing one when an id is given. Text is trimmed and stored as plain text
    // (the storefront escapes it when rendering).
    public Faq save(Faq input) {
        if (input == null) {
            throw new ProductException("An FAQ entry is required");
        }
        String question = input.getQuestion() == null ? "" : input.getQuestion().trim();
        String answer = input.getAnswer() == null ? "" : input.getAnswer().trim();
        if (question.isEmpty() || answer.isEmpty()) {
            throw new ProductException("Both a question and an answer are required");
        }
        if (question.length() > MAX_QUESTION) {
            throw new ProductException("The question can be at most " + MAX_QUESTION + " characters");
        }
        if (answer.length() > MAX_ANSWER) {
            throw new ProductException("The answer can be at most " + MAX_ANSWER + " characters");
        }
        Faq faq;
        if (input.getId() != null) {
            faq = faqs.findById(input.getId()).orElseThrow(() -> new OrderNotFoundException("No FAQ entry with that id"));
        } else {
            if (faqs.count() >= MAX_ENTRIES) {
                throw new ProductException("The FAQ can hold at most " + MAX_ENTRIES + " entries");
            }
            faq = new Faq();
        }
        faq.setQuestion(question);
        faq.setAnswer(answer);
        faq.setSortOrder(input.getSortOrder());
        faq.setUpdatedAt(Instant.now(clock));
        return faqs.save(faq);
    }

    public void delete(long id) {
        if (!faqs.existsById(id)) {
            throw new OrderNotFoundException("No FAQ entry with that id");
        }
        faqs.deleteById(id);
    }
}
