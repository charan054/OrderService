package com.example.orderservice.controller;

import com.example.orderservice.dto.PublicQuestion;
import com.example.orderservice.entity.ProductQuestion;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.ProductQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// GET /questions/product is public (answered questions only, no asker); ask and mine belong to the signed-in
// customer (or the service key); answering, the pending queue and delete are service-key only (SecurityConfig default).
@RestController
@RequestMapping("/questions")
public class ProductQuestionController {
    @Autowired
    private ProductQuestionService service;

    @GetMapping("/product")
    public List<PublicQuestion> answered(@RequestParam int productId) {
        return service.answeredFor(productId);
    }

    @PostMapping("/ask")
    public ProductQuestion ask(@RequestParam long phno, @RequestParam int productId, @RequestParam String question) {
        CustomerAccess.requireSelfOrService(phno);
        return service.ask(phno, productId, question);
    }

    @GetMapping("/mine")
    public List<ProductQuestion> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.mine(phno);
    }

    @GetMapping("/pending")
    public List<ProductQuestion> pending() {
        return service.pending();
    }

    @PutMapping("/{id}/answer")
    public ProductQuestion answer(@PathVariable long id, @RequestParam String answer) {
        return service.answer(id, answer);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
