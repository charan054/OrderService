package com.example.orderservice.controller;

import com.example.orderservice.security.CustomerAccess;
import com.example.orderservice.service.ChatbotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The storefront help chat: a signed-in customer asks about their own orders and payments (or the service key, for testing).
@RestController
@RequestMapping("/chat")
public class ChatbotController {
    public record Ask(long phno, String message) {
    }

    @Autowired
    private ChatbotService chatbot;

    @PostMapping("/ask")
    public ChatbotService.Reply ask(@RequestBody Ask request) {
        CustomerAccess.requireSelfOrService(request.phno());
        return chatbot.ask(request.phno(), request.message());
    }
}
