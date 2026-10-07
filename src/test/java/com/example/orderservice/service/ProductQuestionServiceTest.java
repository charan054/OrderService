package com.example.orderservice.service;

import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.Product;
import com.example.orderservice.dto.PublicQuestion;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.ProductQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductQuestionServiceTest {
    private static final long CUSTOMER = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Mock
    private ProductQuestionRepository questions;
    @Mock
    private ProductClient productClient;

    private ProductQuestionService service;

    @BeforeEach
    void setUp() {
        service = new ProductQuestionService(questions, productClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void productExists() {
        when(productClient.getProductById(1)).thenReturn(new Product());
    }

    private ProductQuestion question(long id, String answer) {
        ProductQuestion q = new ProductQuestion();
        q.setId(id);
        q.setProductId(1);
        q.setAskerPhno(CUSTOMER);
        q.setQuestion("Is it waterproof?");
        q.setAnswer(answer);
        return q;
    }

    @Test
    void askTrimsAndSavesAPendingQuestion() {
        productExists();
        when(questions.save(any(ProductQuestion.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductQuestion q = service.ask(CUSTOMER, 1, "  Is it waterproof?  ");

        assertEquals("Is it waterproof?", q.getQuestion());
        assertEquals(CUSTOMER, q.getAskerPhno());
        assertEquals(NOW, q.getCreatedAt());
        assertNull(q.getAnswer());
    }

    @Test
    void askRejectsTooShortOrTooLongQuestions() {
        assertThrows(ProductException.class, () -> service.ask(CUSTOMER, 1, "  hi "));
        assertThrows(ProductException.class, () -> service.ask(CUSTOMER, 1, null));
        assertThrows(ProductException.class, () -> service.ask(CUSTOMER, 1, "x".repeat(501)));
        verify(questions, never()).save(any());
    }

    @Test
    void askRejectsAnUnknownProduct() {
        when(productClient.getProductById(1)).thenReturn(null);

        assertThrows(ProductException.class, () -> service.ask(CUSTOMER, 1, "Is it waterproof?"));
        verify(questions, never()).save(any());
    }

    @Test
    void askRejectsAnInvalidPhoneNumber() {
        assertThrows(ProductException.class, () -> service.ask(123L, 1, "Is it waterproof?"));
    }

    @Test
    void askIsCappedAtFivePendingQuestionsPerCustomer() {
        productExists();
        when(questions.countByAskerPhnoAndAnswerIsNull(CUSTOMER)).thenReturn(5L);

        assertThrows(ProductException.class, () -> service.ask(CUSTOMER, 1, "Is it waterproof?"));
        verify(questions, never()).save(any());
    }

    @Test
    void publicListHidesTheAsker() {
        ProductQuestion answered = question(7, "Yes, IP67.");
        answered.setAnsweredAt(NOW);
        when(questions.findByProductIdAndAnswerIsNotNullOrderByAnsweredAtDescIdDesc(1)).thenReturn(List.of(answered));

        List<PublicQuestion> result = service.answeredFor(1);

        assertEquals(1, result.size());
        assertEquals(new PublicQuestion(7, "Is it waterproof?", "Yes, IP67.", NOW), result.get(0));
    }

    @Test
    void answerTrimsStampsAndSaves() {
        when(questions.findById(7L)).thenReturn(Optional.of(question(7, null)));
        when(questions.save(any(ProductQuestion.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductQuestion q = service.answer(7, "  Yes, IP67.  ");

        assertEquals("Yes, IP67.", q.getAnswer());
        assertEquals(NOW, q.getAnsweredAt());
    }

    @Test
    void answerRejectsBlankTooLongAndUnknown() {
        assertThrows(ProductException.class, () -> service.answer(7, "   "));
        assertThrows(ProductException.class, () -> service.answer(7, "x".repeat(1001)));
        when(questions.findById(8L)).thenReturn(Optional.empty());
        assertThrows(ProductException.class, () -> service.answer(8, "ok"));
        verify(questions, never()).save(any());
    }

    @Test
    void deleteRejectsUnknownAndDeletesKnown() {
        when(questions.existsById(8L)).thenReturn(false);
        when(questions.existsById(7L)).thenReturn(true);

        assertThrows(ProductException.class, () -> service.delete(8));
        service.delete(7);

        verify(questions).deleteById(7L);
        verify(questions, never()).deleteById(8L);
    }
}
