package com.example.orderservice.service;

import com.example.orderservice.entity.OrderNote;
import com.example.orderservice.exception.OrderNotFoundException;
import com.example.orderservice.exception.ProductException;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.repository.OrderNoteRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Staff-only notes on orders. The controller is service-key only; nothing here is ever shown to a customer. */
@Service
public class OrderNoteService {
    static final int MAX_NOTE_LENGTH = 500;

    private final OrderNoteRepository notes;
    private final CartRepository orders;
    private final Clock clock;

    public OrderNoteService(OrderNoteRepository notes, CartRepository orders, Clock clock) {
        this.notes = notes;
        this.orders = orders;
        this.clock = clock;
    }

    public OrderNote add(long orderId, String text) {
        String note = text == null ? "" : text.trim();
        if (note.isEmpty() || note.length() > MAX_NOTE_LENGTH) {
            throw new ProductException("Note must be 1 to " + MAX_NOTE_LENGTH + " characters");
        }
        if (!orders.existsById(orderId)) {
            throw new OrderNotFoundException("Order not found");
        }
        OrderNote n = new OrderNote();
        n.setOrderId(orderId);
        n.setNote(note);
        n.setCreatedAt(Instant.now(clock));
        return notes.save(n);
    }

    // Newest first. An order with no notes (or no such order) just gives an empty list.
    public List<OrderNote> forOrder(long orderId) {
        return notes.findByOrderIdOrderByIdDesc(orderId);
    }

    public void delete(long noteId) {
        if (!notes.existsById(noteId)) {
            throw new OrderNotFoundException("Note not found");
        }
        notes.deleteById(noteId);
    }
}
