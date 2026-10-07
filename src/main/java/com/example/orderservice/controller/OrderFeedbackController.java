package com.example.orderservice.controller;

import com.example.orderservice.dto.FeedbackSummary;
import com.example.orderservice.entity.OrderFeedback;
import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.OrderFeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// submit / mine: the signed-in customer's own phone (or the service key). summary: service key only (default rule).
@RestController
@RequestMapping("/feedback")
public class OrderFeedbackController {
    @Autowired
    private OrderFeedbackService service;

    @PostMapping("/submit")
    public OrderFeedback submit(@RequestParam long phno, @RequestParam long orderId, @RequestParam int rating,
                                @RequestParam(required = false) Integer deliveryRating,
                                @RequestParam(required = false) String comment) {
        CustomerAccess.requireSelfOrService(phno);
        return service.submit(phno, orderId, rating, deliveryRating, comment);
    }

    @GetMapping("/mine")
    public List<OrderFeedback> mine(@RequestParam long phno) {
        CustomerAccess.requireSelfOrService(phno);
        return service.mine(phno);
    }

    @GetMapping("/summary")
    public FeedbackSummary summary() {
        return service.summary();
    }
}
